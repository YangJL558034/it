$root=Split-Path $PSScriptRoot -Parent
& "$root/.tools/pg-runtime/pgsql/bin/pg_ctl.exe" stop -D "$root/.local/pgdata-dev" -m fast -w
