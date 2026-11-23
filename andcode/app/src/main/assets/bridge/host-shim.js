










































;(function () {
  var token = window.__opencodeHostToken
  var Native = window.WebSocket
  if (!token || typeof Native !== "function") return

  function withToken(url) {
    try {
      var parsed = new URL(url, location.href)
      if (parsed.origin !== location.origin) return url
      if (parsed.searchParams.has("token")) return url
      parsed.searchParams.set("token", token)
      return parsed.toString()
    } catch (error) {
      return url
    }
  }

  var Patched = function (url, protocols) {
    return new Native(withToken(url), protocols)
  }
  Patched.prototype = Native.prototype
  ;["CONNECTING", "OPEN", "CLOSING", "CLOSED"].forEach(function (key) {
    try {
      Patched[key] = Native[key]
    } catch (error) {
      
    }
  })
  window.WebSocket = Patched
})()

;(function () {
  if (window.__fetchTrace) return
  window.__fetchTrace = { reads: 0, sets: 0, firstReads: [] }
  var tracedFetch = window.fetch
  try {
    Object.defineProperty(window, "fetch", {
      configurable: true,
      enumerable: true,
      get: function () {
        var trace = window.__fetchTrace
        trace.reads++
        if (trace.firstReads.length < 200) {
          var stack = ""
          try {
            throw new Error("read")
          } catch (error) {
            stack = String(error.stack || "").split("\n").slice(2, 6).join(" | ").slice(0, 400)
          }
          trace.firstReads.push(stack)
        }
        return tracedFetch
      },
      set: function (value) {
        window.__fetchTrace.sets++
        tracedFetch = value
      },
    })
  } catch (error) {
    window.__fetchTrace.failed = String(error)
  }
  window.__dumpFetchTrace = function () {
    console.log("FTRACE2 " + JSON.stringify(window.__fetchTrace))
  }
})();

;(function () {
  if (window.AndroidBridgeDispatch && typeof window.AndroidBridgeDispatch.dispatch === "function") return

  var nextTicket = 1
  
  
  
  var hostToken = window.__opencodeHostToken || null
  var bridge = {
    dispatch: function (method, argsJson) {
      var ticket = String(nextTicket++)
      try {
        
        
        
        
        
        fetch("/__host/rpc", {
          method: "POST",
          headers: {
            "content-type": "application/json",
            "x-opencode-host-token": hostToken,
          },
          body: JSON.stringify({ ticket: ticket, method: method, args: argsJson }),
        }).catch(function (error) {
          
          
          settle(ticket, false, "host call failed: " + error)
        })
      } catch (error) {
        
        
        throw new Error("host bridge could not send " + method + ": " + error)
      }
      return ticket
    },
  }
  window.AndroidBridgeDispatch = bridge

  function settle(ticket, ok, payload) {
    var target = window.AndroidBridgeDispatch
    
    
    if (!target || typeof target.resolve !== "function") return
    
    
    
    
    
    var value = payload
    if (typeof value === "string") {
      try {
        value = JSON.parse(value)
      } catch (error) {
        value = null
      }
    }
    target.resolve(ticket, ok, value === undefined ? null : value)
  }

  function openEvents() {
    var source
    try {
      
      source = new EventSource("/__host/events?token=" + encodeURIComponent(hostToken || ""))
    } catch (error) {
      console.error("[opencode] host event stream unavailable:", error)
      source = null
    }
    if (source) source.onmessage = dispatchHostMessage
    
    
    if (source) {
      source.onerror = function () {
        console.warn("[opencode] host event stream reconnecting")
      }
    }
    
    
    
    
    
    
    
    
    pollHostMessages()
  }

  
  function dispatchHostMessage(event) {
    var data = event
    if (event && typeof event.data === "string") {
      try {
        data = JSON.parse(event.data)
      } catch (e) {
        return
      }
    }
    if (!data || typeof data !== "object") return
    var target = window.AndroidBridgeDispatch
    if (data.kind === "eval") {
      
      
      try {
        ;(0, eval)(data.script)
      } catch (error) {
        console.error("[opencode] host script failed:", error)
      }
      return
    }
    if (!target || typeof target.emit !== "function") return
    if (data.kind === "emit") {
      target.emit(data.channel, data.payload)
    } else if (data.kind === "resolve") {
      settle(data.ticket, !!data.ok, data.payload)
    }
  }

  function pollHostMessages() {
    fetch("/__host/next", { credentials: "same-origin" })
      .then(function (response) {
        if (response.status !== 200) return null
        return response.json()
      })
      .then(function (data) {
        if (data) dispatchHostMessage(data)
      })
      .catch(function () {})
      .then(function () {
        setTimeout(pollHostMessages, 0)
      })
  }

  
  
  setTimeout(openEvents, 0)

  
  
  
  
  
  
  
  
  
  
  
  
  var revealFocused = function () {
    var active = document.activeElement
    if (!active || active === document.body) return
    var editable =
      active.tagName === "TEXTAREA" ||
      active.tagName === "INPUT" ||
      active.isContentEditable === true ||
      (active.closest && active.closest('[contenteditable="true"]') !== null)
    if (!editable) return
    var target = active.closest ? active.closest('[contenteditable="true"]') || active : active
    try {
      target.scrollIntoView({ block: "nearest", inline: "nearest" })
    } catch (error) {
      target.scrollIntoView(false)
    }
  }

  document.addEventListener(
    "focusin",
    function () {
      setTimeout(revealFocused, 350)
    },
    true,
  )
})()
















;(function () {
  if (window.__hostDiagnostics !== true) return
  window.__hostTracePluginSeeds = true

  
  
  
  
  function say(what, value) {
    try {
      var text = what + " = " + value
      
      if (text.length > 900) text = text.slice(0, 900) + "…"
      fetch("/__probe/" + encodeURIComponent(text), { cache: "no-store" }).catch(function () {})
    } catch (e) {
      
    }
  }

  function describeElectron() {
    var electron = window.electron
    if (!electron) return "missing"
    var keys = []
    for (var k in electron) keys.push(k)
    return "{" + keys.join(",") + "}"
  }

  say("document.readyState", document.readyState)
  say("window.electron", describeElectron())
  say("bootstrap", JSON.stringify(window.electron && window.electron.bootstrap))
  say("storageSnapshot is promise", !!(window.electron && window.electron.storageSnapshot && window.electron.storageSnapshot.then))
  say("plugins is promise", !!(window.electron && window.electron.plugins && window.electron.plugins.then))

  
  
  
  var sawPort = false
  window.addEventListener("message", function (event) {
    if (event && event.data === "desktop-rpc-port") sawPort = true
  })
  setTimeout(function () {
    say("rpc-port message seen", sawPort)
  }, 1500)

  function reportDom(when) {
    var root = document.getElementById("root") || document.body
    say(
      when + " DOM",
      "nodes=" +
        document.querySelectorAll("*").length +
        " rootChildren=" +
        (root ? root.children.length : -1) +
        " bodyText=" +
        JSON.stringify((document.body ? document.body.innerText : "").slice(0, 80)),
    )
  }

  window.addEventListener("error", function (event) {
    say("window error", (event && event.message) || "unknown")
  })
  window.addEventListener("unhandledrejection", function (event) {
    var reason = event && event.reason
    say("unhandled rejection", (reason && (reason.stack || reason.message)) || String(reason))
  })

  setTimeout(function () {
    reportDom("1s")
  }, 1000)
  setTimeout(function () {
    
    
    say("later window.electron", describeElectron())
    say("later bootstrap", JSON.stringify(window.electron && window.electron.bootstrap))
    say("later snapshot promise", !!(window.electron && window.electron.storageSnapshot && window.electron.storageSnapshot.then))
    say("later plugins promise", !!(window.electron && window.electron.plugins && window.electron.plugins.then))
    var html = document.body ? document.body.innerHTML : ""
    say("body html", html.slice(0, 700))
  }, 2000)
  setTimeout(function () {
    reportDom("3s")
  }, 3000)
  setTimeout(function () {
    
    
    var names = []
    var nodes = document.querySelectorAll("[data-component]")
    for (var i = 0; i < nodes.length && names.length < 40; i++) {
      names.push(nodes[i].getAttribute("data-component"))
    }
    say("mounted components", names.join(","))
    say("location", location.pathname + " | " + location.href.slice(0, 120))
    var keys = []
    try {
      for (var k = 0; k < localStorage.length && keys.length < 20; k++) keys.push(localStorage.key(k))
    } catch (e) {
      keys.push("localStorage threw")
    }
    say("localStorage keys", keys.join(","))
  }, 4000)
  setTimeout(function () {
    reportDom("8s")
  }, 8000)
})()
