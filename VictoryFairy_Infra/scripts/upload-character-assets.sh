#!/usr/bin/env bash
# upload-character-assets.sh — 캐릭터 꾸미기 에셋(SVG)을 victoryfairy-asset 버킷에 올린다.
#
# 사용법:
#   ./scripts/upload-character-assets.sh "/c/Users/<user>/Desktop/Character Asset"
#   DRY_RUN=1 ./scripts/upload-character-assets.sh "<source>"   # 올릴 목록만 출력
#
# 전제:
#   - AWS 자격 증명 (버킷 victoryfairy-asset 에 PutObject)
#   - 원본 디렉터리 구조:
#       <source>/[Character] Basic.svg
#       <source>/for character/{cloth,head,item}/*.svg   → 착용용(캐릭터 정합 좌표계 160x200)
#       <source>/for shop/{cloth,head,item}/*.svg        → 상점 진열용(단독 좌표계 80x80)
#
# 만드는 키(= DB 에 저장되는 EP. BaseURL 은 붙이지 않는다):
#   characters/victory-fairy.svg
#   items/<부위>/<슬러그>.svg    (착용용 → character_items.using_img)
#   stores/<부위>/<슬러그>.svg   (상점용 → character_items.display_img)
#
# ⚠ 원본 파일명(대괄호·공백·"Name=" 접두사)을 그대로 키로 쓰지 않는다. URL 인코딩이 필요한 키는
#   CDN 경로에서 매번 사고를 부르고, 무엇보다 상점본과 착용본의 원본 이름이 서로 달라(색상명 vs
#   구단명) 그대로 두면 두 컬럼을 이름으로 짝지을 수 없다. scripts/character-assets.tsv 가 그 짝과
#   슬러그의 단일 출처다.
#
# ⚠ 재실행 안전하다(같은 키에 같은 내용을 덮어쓴다). 다만 CloudFront 는 이미 캐시한 객체를 계속
#   내보내므로, 그림을 교체했다면 무효화가 따로 필요하다:
#     aws cloudfront create-invalidation --distribution-id E1ZZQDZFGJGL2K \
#       --paths '/characters/*' '/items/*' '/stores/*'

set -euo pipefail

BUCKET="${BUCKET:-victoryfairy-asset}"
SOURCE="${1:-}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MAPPING="${SCRIPT_DIR}/character-assets.tsv"
CONTENT_TYPE="image/svg+xml"
# 슬러그 키가 고정이라 더 오래 캐싱해도 되지만, 그림 교체가 실제로 일어나는 단계라 하루로 둔다
# (교체 시 위 무효화 한 번이면 전 세계에 반영된다).
CACHE_CONTROL="public, max-age=86400"

if [[ -z "${SOURCE}" ]]; then
  echo "사용법: $0 <에셋 원본 디렉터리>" >&2
  exit 1
fi
if [[ ! -d "${SOURCE}" ]]; then
  echo "원본 디렉터리가 없습니다: ${SOURCE}" >&2
  exit 1
fi
if [[ ! -f "${MAPPING}" ]]; then
  echo "매핑 파일이 없습니다: ${MAPPING}" >&2
  exit 1
fi

# 원본을 임시 디렉터리에 안전한 이름으로 복사해 두는 자리.
#
# ⚠ 우회가 아니라 필수다: AWS CLI 는 --body 경로에 대괄호가 들어가면 실제로 존재하는 파일인데도
#   "Blob values must be a path to a file" 로 거절한다. 디자이너 원본이 정확히 그런 이름이라
#   ([Uniform] Bears 1.svg) 착용용 24개가 통째로 실패한다 — 상점용은 대괄호가 없어 멀쩡히 올라가는
#   탓에, 이 복사가 없으면 "절반만 올라간" 상태가 만들어진다.
STAGE="$(mktemp -d)"
trap 'rm -rf "${STAGE}"' EXIT

put() {
  local src="$1" key="$2"
  if [[ ! -f "${src}" ]]; then
    # 조용히 건너뛰지 않는다 — 한 짝이 빠지면 상점에 깨진 이미지가 그대로 걸린다.
    echo "  ! 원본 없음: ${src}" >&2
    return 1
  fi
  if [[ -n "${DRY_RUN:-}" ]]; then
    echo "  (dry-run) ${key}  <-  ${src}"
    return 0
  fi

  local staged="${STAGE}/staged.svg"
  cp "${src}" "${staged}"

  # ⚠ aws 의 종료 코드를 반드시 직접 본다. 이 함수는 호출부에서 `|| failed=1` 로 불리는데, 그
  #   문맥에서는 errexit 이 함수 전체에 걸쳐 꺼지므로 aws 가 실패해도 다음 줄이 그대로 실행된다
  #   — 실제로 그렇게 해서 실패한 업로드가 "+" 성공 줄로 찍힌 적이 있다.
  if ! aws s3api put-object \
      --bucket "${BUCKET}" \
      --key "${key}" \
      --body "${staged}" \
      --content-type "${CONTENT_TYPE}" \
      --cache-control "${CACHE_CONTROL}" \
      --output text --query 'ETag' >/dev/null; then
    echo "  ! 업로드 실패: ${key}" >&2
    rm -f "${staged}"
    return 1
  fi
  rm -f "${staged}"
  echo "  + ${key}"
}

failed=0

echo "[1/2] 캐릭터"
put "${SOURCE}/[Character] Basic.svg" "characters/victory-fairy.svg" || failed=1

echo "[2/2] 아이템 (착용용 items/ · 상점용 stores/)"
# IFS 를 탭으로 고정한다 — 표시명에 공백이 들어 있어 기본 IFS 로는 열이 갈라진다.
while IFS=$'\t' read -r part slug shop_file wear_file _label; do
  [[ -z "${part}" || "${part}" == \#* ]] && continue
  put "${SOURCE}/for character/${part}/${wear_file}" "items/${part}/${slug}.svg" || failed=1
  put "${SOURCE}/for shop/${part}/${shop_file}"      "stores/${part}/${slug}.svg" || failed=1
done < "${MAPPING}"

if [[ "${failed}" -ne 0 ]]; then
  echo "일부 파일이 올라가지 않았습니다 — 위 '!' 줄을 확인하세요." >&2
  exit 1
fi
echo "완료: s3://${BUCKET}"
