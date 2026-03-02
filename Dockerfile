# set up the main image with dependencies first, to avoid re-doing this after each build
FROM amazoncorretto:11-alpine as deps

WORKDIR /EternalJukebox

RUN wget https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp -O /usr/local/bin/yt-dlp \
    && chmod a+rx /usr/local/bin/yt-dlp

RUN apk update \
    && apk add --no-cache nodejs npm ffmpeg gettext python3 \
    && touch hikari.properties

# build jar with gradle

FROM gradle:8-jdk11 as gradle-build

WORKDIR /home/gradle/project

# Only copy dependency-related files
COPY build.gradle gradle.propertie* settings.gradle ./EternalJukebox/

# Only download dependencies
# Eat the expected build failure since no source code has been copied yet
RUN gradle clean shadowJar --no-daemon > /dev/null 2>&1 || true

COPY . ./EternalJukebox

WORKDIR /home/gradle/project/EternalJukebox

RUN gradle clean shadowJar --no-daemon

# build web with jekyll

FROM rockstorm/jekyll:latest as jekyll-build

WORKDIR /EternalJukebox

COPY --from=gradle-build /home/gradle/project/EternalJukebox .

RUN chmod -R 777 . && jekyll build --source _web --destination web

# copy into main image

FROM deps as main

COPY --from=jekyll-build /EternalJukebox/ ./
COPY --from=gradle-build /home/gradle/project/EternalJukebox/build/libs/* ./

# envsubst is used so environment variables can be used instead of a config file

CMD envsubst < "/EternalJukebox/envvar_config.yaml" > "/EternalJukebox/config.yaml"\
    && java -jar EternalJukebox-all.jar
