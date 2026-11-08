export const MAX_ATTACHMENT_BYTES=50*1024*1024
export function assertAttachmentFile(file) {
  if(!file || !Number.isSafeInteger(file.size) || file.size<=0) throw new Error('不能发送空文件')
  if(file.size>MAX_ATTACHMENT_BYTES) throw new Error('文件不能超过50MB')
  if(!file.name || file.name.length>255) throw new Error('文件名不合法或过长')
}
export function configureAttachmentInput(input,kind) {
  if(!input) return
  if(kind==='image') input.setAttribute('accept','image/png,image/jpeg,image/gif,image/webp')
  else input.removeAttribute('accept')
  input.click()
}
