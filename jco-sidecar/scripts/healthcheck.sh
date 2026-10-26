#!/bin/sh
# Probe the existing SAP-aware endpoint without starting a second JVM.
# Requires POSIX sh and curl >= 7.55.0 (--header @-).
set +x
exec 2>/dev/null
set -eu

hc_token=${RFC_GUARD_API_TOKEN-}
case "$hc_token" in
  *[![:space:]]*) ;;
  *)
    hc_token_file=${RFC_GUARD_API_TOKEN_FILE-}
    [ -n "$hc_token_file" ] && [ -f "$hc_token_file" ] && [ -r "$hc_token_file" ] || exit 1
    # Read a mounted token without passing its value to another process.
    hc_token=$(
      while IFS= read -r hc_line || [ -n "$hc_line" ]; do
        printf '%s\n' "$hc_line"
      done < "$hc_token_file"
    ) || exit 1
    ;;
esac
case "$hc_token" in *[![:space:]]*) ;; *) exit 1 ;; esac
# Reject control characters, including CR/LF header injection. Quoted printf
# treats token punctuation literally; curl receives the header only on stdin.
LC_ALL=C
export LC_ALL
case "$hc_token" in *[![:print:]]*) exit 1 ;; esac
unset RFC_GUARD_API_TOKEN RFC_GUARD_API_TOKEN_FILE

hc_port=${PORT-8080}
case "$hc_port" in ''|*[!0-9]*) exit 1 ;; esac
[ "$hc_port" -ge 1 ] && [ "$hc_port" -le 65535 ] || exit 1
hc_scheme=https
[ "${RFC_GUARD_MODE-}" != student-provisioning ] || hc_scheme=http

# -q must be first: ignore .curlrc; ignore proxies and never follow redirects.
# HTTPS is loopback only and retains the Java probe's self-signed certificate
# behavior. No retry here: Docker already controls interval/retries.
hc_status=$(
  printf 'Authorization: Bearer %s\n' "$hc_token" |
    curl -q --silent --noproxy '*' --proto '=http,https' \
      --connect-timeout 4 --max-time 4 --retry 0 --insecure \
      --request GET --header @- --output /dev/null --write-out '%{http_code}' \
      --url "$hc_scheme://127.0.0.1:$hc_port/v1/health"
) || exit 1
[ "$hc_status" = 200 ]
