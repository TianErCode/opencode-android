














;(function () {
  
  
  
  
  
  
  
  
  if (typeof Promise.withResolvers !== "function") {
    Promise.withResolvers = function () {
      var resolve
      var reject
      var promise = new Promise(function (res, rej) {
        resolve = res
        reject = rej
      })
      return { promise: promise, resolve: resolve, reject: reject }
    }
  }

  if (typeof Map.groupBy !== "function") {
    Map.groupBy = function (items, keyOf) {
      var out = new Map()
      for (var i = 0; i < items.length; i++) {
        var item = items[i]
        var key = typeof keyOf === "function" ? keyOf(item, i) : item[keyOf]
        var bucket = out.get(key)
        if (bucket === undefined) {
          bucket = []
          out.set(key, bucket)
        }
        bucket.push(item)
      }
      return out
    }
  }

  if (window.api) return

  var pending = Object.create(null)
  var listeners = Object.create(null)

  function emit(channel, payload) {
    var subs = listeners[channel]
    if (!subs) return
    subs.forEach(function (fn) {
      try {
        fn(payload)
      } catch (e) {
        console.error("[opencode] listener for " + channel + " threw", e)
      }
    })
  }

  function subscribe(channel, cb) {
    ;(listeners[channel] || (listeners[channel] = [])).push(cb)
    return function () {
      var subs = listeners[channel]
      if (!subs) return
      var i = subs.indexOf(cb)
      if (i >= 0) subs.splice(i, 1)
    }
  }

  function call(method) {
    var args = Array.prototype.slice.call(arguments, 1)
    return new Promise(function (resolve, reject) {
      var payload
      try {
        payload = JSON.stringify(args)
      } catch (e) {
        reject(e)
        return
      }
      var ticket
      try {
        if (!native) throw new Error("Android bridge unavailable")
        
        
        
        ticket = native.dispatch(method, payload)
      } catch (e) {
        reject(e)
        return
      }
      
      
      pending[ticket] = { resolve: resolve, reject: reject, method: method }
    })
  }

  
  
  
  
  
  
  var native = window.AndroidBridgeDispatch
  if (!native || typeof native.dispatch !== "function") {
    
    
    
    console.error("[opencode] Android bridge unavailable; window.api calls will reject")
    native = null
  }
  
  
  function attachNativeCallbacks() {
    if (!native) return
    native.resolve = function (ticket, ok, payload) {
      var entry = pending[ticket]
      if (!entry) return
      delete pending[ticket]
      if (!ok) {
        entry.reject(new Error(payload == null ? entry.method + " failed" : String(payload)))
        return
      }
      entry.resolve(payload == null ? undefined : payload)
    }
    native.emit = function (channel, payloadJson) {
      var payload = payloadJson
      if (typeof payload === "string") {
        try {
          payload = JSON.parse(payload)
        } catch (e) {
          payload = null
        }
      }
      emit(channel, payload)
    }
  }
  attachNativeCallbacks()

  
  
  function bytesToBase64(bytes) {
    var view = new Uint8Array(bytes)
    var chunk = 0x8000
    var parts = []
    for (var i = 0; i < view.length; i += chunk) {
      parts.push(String.fromCharCode.apply(null, view.subarray(i, i + chunk)))
    }
    return btoa(parts.join(""))
  }

  function base64ToBytes(b64) {
    var bin = atob(b64)
    var out = new Uint8Array(bin.length)
    for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i)
    return out.buffer
  }

  function withBuffer(promise) {
    return promise.then(function (b64) {
      return b64 == null ? null : base64ToBytes(b64)
    })
  }

  window.api = {
    platform: "desktop",

    

    
    awaitInitialization: function () {
      return call("awaitInitialization")
    },
    
    
    
    
    
    
    
    sidecarAuthorization: function () {
      return call("sidecarAuthorization")
    },
    getWindowID: function () {
      return call("getWindowID")
    },
    consumeInitialDeepLinks: function () {
      return call("consumeInitialDeepLinks")
    },
    onDeepLink: function (cb) {
      return subscribe("deepLink", cb)
    },
    onMenuCommand: function (cb) {
      
      
      return subscribe("menuCommand", cb)
    },

    updater: {
      subscribe: function (cb) {
        var off = subscribe("updater", cb)
        call("updaterState").then(cb, function () {})
        return Promise.resolve(off)
      },
      check: function () {
        return call("updaterCheck")
      },
      install: function () {
        return call("updaterInstall")
      },
    },

    

    storeGet: function (name, key) {
      return call("storeGet", name, key)
    },
    storeSet: function (name, key, value) {
      return call("storeSet", name, key, value)
    },
    storeDelete: function (name, key) {
      return call("storeDelete", name, key)
    },
    storeClear: function (name) {
      return call("storeClear", name)
    },
    storeKeys: function (name) {
      return call("storeKeys", name)
    },
    storeLength: function (name) {
      return call("storeLength", name)
    },
    draftGet: function (key) {
      return call("draftGet", key)
    },
    draftSet: function (key, value) {
      return call("draftSet", key, value)
    },
    draftDelete: function (key) {
      return call("draftDelete", key)
    },
    draftBlobPut: function (data) {
      return call("draftBlobPut", bytesToBase64(data))
    },
    draftBlobGet: function (id) {
      return withBuffer(call("draftBlobGet", id))
    },

    

    openDirectoryPicker: function (opts) {
      return call("openDirectoryPicker", opts || {})
    },
    openFilePicker: function (opts) {
      return call("openFilePicker", opts || {})
    },
    readPickedFile: function (token, p) {
      return withBuffer(call("readPickedFile", token, p))
    },
    releasePickedFiles: function (token) {
      return call("releasePickedFiles", token)
    },
    saveFilePicker: function (opts) {
      return call("saveFilePicker", opts || {})
    },
    
    
    
    getPathForFile: function (file) {
      return file && file.name ? file.name : ""
    },
    readClipboardImage: function () {
      return call("readClipboardImage").then(function (r) {
        if (!r) return null
        return { buffer: base64ToBytes(r.buffer), width: r.width, height: r.height }
      })
    },

    

    openExternal: function (url) {
      call("openExternal", url).catch(function () {})
    },
    openLocalFile: function () {
      return call("unsupported").catch(function () {})
    },
    openPath: function () {
      return call("unsupported")
    },
    revealPath: function () {
      return call("unsupported")
    },
    checkAppExists: function () {
      return call("checkAppExists")
    },
    resolveAppPath: function () {
      return call("resolveAppPath")
    },
    installCli: function () {
      return call("unsupported")
    },
    showWindow: function () {
      call("showWindow").catch(function () {})
    },
    setWindowFocus: function () {
      call("setWindowFocus").catch(function () {})
    },
    getWindowFocused: function () {
      return call("getWindowFocused")
    },
    getWindowFullscreen: function () {
      return call("getWindowFullscreen")
    },
    onWindowFullscreenChanged: function (cb) {
      return subscribe("windowFullscreen", cb)
    },
    relaunch: function () {
      call("relaunch").catch(function () {})
    },
    killSidecar: function () {
      return call("killSidecar")
    },

    

    getZoomFactor: function () {
      return call("getZoomFactor")
    },
    setZoomFactor: function (factor) {
      return call("setZoomFactor", factor)
    },
    onZoomFactorChanged: function (cb) {
      return subscribe("zoomFactor", cb)
    },
    getPinchZoomEnabled: function () {
      return call("getPinchZoomEnabled")
    },
    setPinchZoomEnabled: function (enabled) {
      return call("setPinchZoomEnabled", enabled)
    },
    onPinchZoomEnabledChanged: function (cb) {
      return subscribe("pinchZoomEnabled", cb)
    },
    runDesktopMenuAction: function (action) {
      return call("runDesktopMenuAction", action)
    },

    

    setBackgroundColor: function (color) {
      call("setBackgroundColor", color).catch(function () {})
    },
    setTitlebar: function () {
      return call("unsupported")
    },
    setForceFocus: function (enabled) {
      call("setForceFocus", enabled).catch(function () {})
    },
    setNativeTranslations: function () {
      
      return Promise.resolve()
    },
    getDisplayBackend: function () {
      return call("getDisplayBackend")
    },
    setDisplayBackend: function () {
      return call("unsupported")
    },

    

    exportDebugLogs: function () {
      return call("exportDebugLogs")
    },
    recordFatalRendererError: function (error) {
      call("recordFatalRendererError", error).catch(function () {})
    },

    

    getDefaultServerUrl: function () {
      return call("getDefaultServerUrl")
    },
    setDefaultServerUrl: function (url) {
      return call("setDefaultServerUrl", url == null ? null : url)
    },
    isFirstLaunchOnboardingPending: function () {
      return call("isFirstLaunchOnboardingPending")
    },
    finishFirstLaunchOnboarding: function () {
      return call("finishFirstLaunchOnboarding")
    },
    isOldLayoutEligible: function () {
      return call("isOldLayoutEligible")
    },

    
    
    wslServers: undefined,
  }

  
  
  window.api.runtime = {
    getState: function () {
      return call("runtime.getState")
    },
    subscribe: function (cb) {
      return subscribe("runtime", function (snapshot) {
        cb({ type: "state", state: snapshot })
      })
    },
    install: function () {
      return call("runtime.install")
    },
    start: function () {
      return call("runtime.start")
    },
    
    
    requestStorageAccess: function () {
      return call("runtime.requestStorageAccess")
    },
    stop: function () {
      return call("runtime.stop")
    },
    checkUpdate: function () {
      return call("runtime.checkUpdate")
    },
    update: function () {
      return call("runtime.update")
    },
    rollback: function () {
      return call("runtime.rollback")
    },
    reinstall: function () {
      return call("runtime.reinstall")
    },
    delete: function () {
      return call("runtime.delete")
    },
    retry: function () {
      return call("runtime.retry")
    },
  }

})()
