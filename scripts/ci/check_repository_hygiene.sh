#!/usr/bin/env bash
set -euo pipefail

fail() {
  printf 'repository hygiene: %s\n' "$1" >&2
  exit 1
}

mapfile -t tracked_files < <(git ls-files)

bad_artifacts="$(printf '%s\n' "${tracked_files[@]}" | grep -E '(^|/)([^/]+\.(orig|bak|rej)|[^/]*~)$' || true)"
if [[ -n "$bad_artifacts" ]]; then
  printf '%s\n' "$bad_artifacts" >&2
  fail 'backup/reject artifacts are tracked'
fi

bad_secret_paths="$(printf '%s\n' "${tracked_files[@]}" | grep -E '(^|/)(\.env($|\.)|[^/]+\.(jks|keystore|p12|pfx|pem|key)|id_rsa|id_ed25519)$' || true)"
if [[ -n "$bad_secret_paths" ]]; then
  printf '%s\n' "$bad_secret_paths" >&2
  fail 'secret-bearing file types are tracked'
fi

mapfile -t production_sources < <(
  printf '%s\n' "${tracked_files[@]}" \
    | grep -E '(^|/)src/main/.*\.(kt|java)$' || true
)

if (( ${#production_sources[@]} > 0 )); then
  set +e
  stub_scan="$(git grep -nE '(^|[^[:alnum:]_])(FIXME|HACK)([^[:alnum:]_]|$)|TODO[(]|NotImplementedError|UnsupportedOperationException[(]"Not implemented' -- "${production_sources[@]}" 2>&1)"
  stub_status=$?
  set -e
  case "$stub_status" in
    0)
      printf '%s\n' "$stub_scan" >&2
      fail 'production stub or unresolved marker found'
      ;;
    1) ;;
    *)
      printf '%s\n' "$stub_scan" >&2
      fail "git grep failed while scanning production stubs (status $stub_status)"
      ;;
  esac
fi

mapfile -t text_files < <(
  printf '%s\n' "${tracked_files[@]}" \
    | grep -E '\.(kt|kts|java|xml|md|yml|yaml|toml|properties|sh|py|txt)$' || true
)

# Байт NUL в исходнике делает файл двоичным для git, и тогда все текстовые
# проверки ниже молча его пропускают: они идут с `git grep -I`. Поэтому проверка
# стоит перед ними. Уже случалось трижды: строка вида "shell:id\u0000" в коде
# оказывалась настоящим нулевым байтом, компилировалась и проходила все гейты.
#
# Смотрит и на **неотслеживаемые** файлы, в отличие от проверок ниже. Четвёртый
# случай был именно таким: новый файл с нулевым байтом гейт пропускал, потому
# что `git ls-files` его ещё не знал, — и поймать его можно было только после
# коммита, то есть когда он уже в истории. Проверка читает файлы сама, а не
# через `git grep`, поэтому расширить ей список ничего не стоит.
mapfile -t nul_candidates < <(
  git ls-files --cached --others --exclude-standard \
    | grep -E '\.(kt|kts|java|xml|md|yml|yaml|toml|properties|sh|py|txt)$' || true
)

if (( ${#nul_candidates[@]} > 0 )); then
  binary_text_files=""
  for text_file in "${nul_candidates[@]}"; do
    [[ -f "$text_file" ]] || continue
    if ! LC_ALL=C tr -d '\000' < "$text_file" | cmp -s - "$text_file"; then
      binary_text_files+="$text_file"$'\n'
    fi
  done
  if [[ -n "$binary_text_files" ]]; then
    printf '%s' "$binary_text_files" >&2
    fail 'NUL byte in a text file (write \u0000 as an escape, not as the byte)'
  fi
fi

if (( ${#text_files[@]} > 0 )); then
  set +e
  whitespace_scan="$(git grep -nI -E '[[:blank:]]+$' -- "${text_files[@]}" 2>&1)"
  whitespace_status=$?
  set -e
  case "$whitespace_status" in
    0)
      printf '%s\n' "$whitespace_scan" >&2
      fail 'trailing whitespace found in tracked text files'
      ;;
    1) ;;
    *)
      printf '%s\n' "$whitespace_scan" >&2
      fail "git grep failed while scanning trailing whitespace (status $whitespace_status)"
      ;;
  esac
fi

printf 'repository hygiene: PASS\n'
