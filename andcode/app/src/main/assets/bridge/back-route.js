











;(function () {
  if (window.__opencodeBackRoute) return
  window.__opencodeBackRoute = true

  var bridge = window.AndroidBridgeDispatch
  if (!bridge || typeof bridge.dispatch !== "function") return

  function report() {
    try {
      bridge.dispatch("AppReportRoute", JSON.stringify([location.pathname]))
    } catch (error) {
      
    }
  }

  var push = history.pushState
  var replace = history.replaceState

  history.pushState = function () {
    var result = push.apply(this, arguments)
    report()
    return result
  }
  history.replaceState = function () {
    var result = replace.apply(this, arguments)
    report()
    return result
  }

  window.addEventListener("popstate", report)
  window.addEventListener("hashchange", report)

  report()
})()
