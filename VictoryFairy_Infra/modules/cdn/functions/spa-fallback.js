// SPA fallback — nginx.conf 의 `try_files $uri $uri/ /index.html` 대체.
//
// ⚠ custom error response(404 → /index.html)로 하지 않는 이유: 그 설정은 behavior 단위가 아니라
//   distribution 전역이라, API 가 내린 404·403 까지 index.html + 200 으로 바꿔버린다. FE 는
//   "성공했는데 JSON 이 아닌 HTML" 을 받는다. 함수는 S3 behavior 에만 붙어 API 를 건드리지 않는다.
//
// ⚠ /assets/* 는 별도 behavior 로 갈라져 이 함수가 붙지 않는다. 붙으면 없는 청크 요청이
//   index.html + 200 으로 돌아와 "Unexpected token '<'" 로 번진다(nginx 의 try_files =404 와 같은 방어).
function handler(event) {
    var request = event.request;

    // 마지막 세그먼트에 확장자가 없으면 라우터가 해석할 딥링크로 본다(/login, /chat/123, / …).
    // 확장자가 있으면 실제 파일 요청이므로 그대로 보내 없는 파일은 S3 가 404 를 내게 둔다.
    var uri = request.uri;
    var lastSegment = uri.substring(uri.lastIndexOf('/') + 1);

    if (lastSegment.indexOf('.') === -1) {
        request.uri = '/index.html';
    }

    return request;
}
