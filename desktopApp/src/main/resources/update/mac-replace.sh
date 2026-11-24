#!/bin/sh
# 준비 신호 뒤에는 표준 오류만 사용한다.
set -u
pid=$1
installed=$2
staged=$3
old_id=$4
marker=$5
image=$6
stage_dir=${staged%/*}
keep_backup=false

identity() {
    /usr/bin/stat -f '%d:%i' "$1"
}

swap_apps() {
    # macOS renamex_np의 RENAME_SWAP은 같은 볼륨의 두 디렉터리를 한 번에 교환한다.
    /usr/bin/osascript -l JavaScript - "$installed" "$staged" <<'JXA'
ObjC.bindFunction('renamex_np', ['int', ['char *', 'char *', 'unsigned int']]);
function run(paths) {
    if ($.renamex_np(paths[0], paths[1], 2) !== 0) throw Error('앱 디렉터리 교환 실패');
}
JXA
}

cleanup() {
    if [ "$keep_backup" = false ]; then /bin/rm -rf "$stage_dir"; fi
    /bin/rm -f "$image" "$0"
    /bin/rmdir "${image%/*}" 2>/dev/null || :
}

report() {
    printf '%s\n' "$1" >&2
    printf '%s\n' "$1" > "$marker"
}

# flock의 잠금은 이 셸의 fd 3에 걸리며 프로세스 종료 시 해제된다. LOCK_EX | LOCK_NB = 6.
# macOS 15 이전에도 있는 libSystem 호출을 사용한다.
exec 3>>"${marker%/*}/mac-install.lock" || exit 1
if ! /usr/bin/osascript -l JavaScript - >/dev/null <<'JXA'
ObjC.bindFunction('flock', ['int', ['int', 'int']]);
if ($.flock(3, 6) !== 0) throw Error('설치 잠금 획득 실패');
JXA
then
    printf 'busy\n'
    exit 1
fi

# 사용자가 준비 중 앱을 이동하거나 다른 인스턴스가 먼저 업데이트하면 현재 프로세스를 닫지 않는다.
new_id=$(identity "$staged") || exit 1
if [ "$(identity "$installed")" != "$old_id" ]; then
    printf 'changed\n'
    exit 1
fi
printf 'ready\n'
exec 1>&2
trap cleanup EXIT

while /bin/kill -0 "$pid" 2>/dev/null; do /bin/sleep 0.2; done

if [ "$(identity "$installed")" != "$old_id" ]; then
    report '업데이트 중 설치된 앱이 변경되어 교체를 중단했습니다.'
    exit 1
fi
if ! swap_apps; then
    report '설치 폴더에서 앱을 교체할 수 없습니다. 쓰기 권한과 볼륨 형식을 확인해 주세요.'
    /usr/bin/open -n "$installed" 3>&-
    exit 1
fi
if ! /usr/bin/open -n "$installed" 3>&-; then
    if [ "$(identity "$installed")" = "$new_id" ] && [ "$(identity "$staged")" = "$old_id" ] && swap_apps; then
        report '새 버전을 실행할 수 없어 이전 버전을 복원했습니다.'
        /usr/bin/open -n "$installed" 3>&-
    else
        keep_backup=true
        report "새 버전을 실행할 수 없습니다. 이전 앱 보관 위치: $staged"
    fi
    exit 1
fi
