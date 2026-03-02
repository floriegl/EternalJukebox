yt-dlp --js-runtimes node --remote-components ejs:github --audio-format $3 -x -o $2 --max-filesize 100m --no-playlist --max-downloads 1 --playlist-end 1 --exec "echo Video ID: %(id)s" -- "$1"
