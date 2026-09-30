# Starship asset

Place the legally obtained, statically linked Android **arm64-v8a/aarch64**
`starship` executable next to this file as one of:

- `starship/aarch64/starship`
- `starship/arm64-v8a/starship`
- `starship`

`StarshipInstaller` validates the ELF class and `EM_AARCH64`, copies it
atomically to app-private storage, marks it executable, and writes a private
`.bashrc`. On Android 10 and later a writable app-private file cannot be
executed by `execve`; the copied file is therefore a staged/profile artifact,
not a runnable local process. The archive intentionally contains no third-party
binary.
