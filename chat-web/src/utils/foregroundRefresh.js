// Shared lifecycle for a visible, focused conversation. No overlapping requests or background polling.
export function createForegroundRefresh({refresh,active,windowTarget=window,documentTarget=document,
  schedule=setTimeout,cancel=clearTimeout,interval=60000,onError=console.warn}) {
  let running=false,timer=null,pending=null
  const clear=()=>{if(timer!=null) cancel(timer);timer=null}
  const plan=()=>{clear();if(running && active()) timer=schedule(run,interval)}
  function run() {
    clear()
    if(!running || !active()) return Promise.resolve()
    if(pending) return pending
    const task=Promise.resolve().then(()=>{if(running && active()) return refresh()}).catch(onError)
    pending=task
    return task.finally(()=>{if(pending===task) pending=null;plan()})
  }
  const foreground=()=>{if(active()) run();else clear()}
  return {
    refresh:run,
    start(){if(running)return;running=true;windowTarget.addEventListener('focus',foreground);windowTarget.addEventListener('blur',foreground);documentTarget.addEventListener('visibilitychange',foreground);run()},
    stop(){running=false;clear();windowTarget.removeEventListener('focus',foreground);windowTarget.removeEventListener('blur',foreground);documentTarget.removeEventListener('visibilitychange',foreground)},
  }
}
