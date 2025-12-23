import request from '@/utils/request'

export function register(data) {
    return request.post("user/register", data)
}
export function login(data) {
    return request.post("user/login", data)
}
export function userInfo() {
    return request.get("user/getInfo")
}
export function readNotice(id) {
    return request.put("notice/read/" + id)
}
export function noticeList(type, data) {
    return request.post("notice/list/" + type, data)
}