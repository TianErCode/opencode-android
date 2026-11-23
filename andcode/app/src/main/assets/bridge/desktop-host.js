




























;(function () {
  if (window.__opencodeDesktopHost) return
  window.__opencodeDesktopHost = true

  
  
  
  var api = window.api
  var unknown = Object.create(null)

  
  
  var EDIT_COMMANDS = {
    "edit.copy": "copy",
    "edit.cut": "cut",
    "edit.paste": "paste",
    "edit.selectAll": "selectAll",
    "edit.undo": "undo",
    "edit.redo": "redo",
    "edit.delete": "delete",
  }

  
  
  var BUILTIN_EXTENSIONS = [
    "usage",
    "btw",
    "debug",
    "terminal",
    "file",
    "review",
    "summary",
    "browser",
    "pairing",
    "updater",
    "ssh",
  ]

  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  var BUILTIN_EXTENSIONS_ENABLED = false

  







  function extensionEnabled(id) {
    var flag = BUILTIN_EXTENSIONS_ENABLED
    if (typeof flag === "boolean") return flag
    if (typeof flag !== "string") return false
    return flag.split(",").indexOf(id) >= 0
  }

  
  
  
  
  
  
  var EXTENSION_STORE_KEY = "opencode-extensions"
  var enabledOverrides = null
  var revisions = null

  function hostStore() {
    try {
      return api.storeGet(EXTENSION_STORE_KEY, "enabled").then(function (raw) {
        return parse(raw, {})
      })
    } catch (e) {
      return Promise.resolve({})
    }
  }

  
  
  function hostStoreSet(key, value) {
    return api.storeSet(EXTENSION_STORE_KEY, key, JSON.stringify(value))
  }

  function loadEnabled() {
    if (enabledOverrides) return Promise.resolve(enabledOverrides)
    return hostStore().then(function (saved) {
      enabledOverrides = saved
      return enabledOverrides
    })
  }

  function loadRevisions() {
    if (revisions) return Promise.resolve(revisions)
    return api.storeGet(EXTENSION_STORE_KEY, "revision").then(function (raw) {
      revisions = parse(raw, {})
      return revisions
    })
  }

  function parse(raw, fallback) {
    try {
      var value = JSON.parse(String(raw == null ? "" : raw))
      return value && typeof value === "object" ? value : fallback
    } catch (e) {
      return fallback
    }
  }

  function isExtensionEnabled(id) {
    if (!id) return false
    if (enabledOverrides && Object.prototype.hasOwnProperty.call(enabledOverrides, id)) {
      return enabledOverrides[id] === true
    }
    return extensionEnabled(id)
  }

  function extensionRevision(id) {
    return (revisions && revisions[id]) || 0
  }

  
  
  
  
  
  
  
  
  
  
  
  var REPORT_EXTENSION_FAILURE = false

  
  function extensionError(id) {
    if (!REPORT_EXTENSION_FAILURE || !isExtensionEnabled(id)) return undefined
    return "This host does not run the extension host"
  }

  
  function announceExtensions() {
    return rendererVersion().then(function (version) {
      var list = BUILTIN_EXTENSIONS.map(function (id) {
        return {
          id: id,
          name: id,
          version: version,
          builtin: true,
          enabled: isExtensionEnabled(id),
          revision: String(extensionRevision(id)),
        }
      })
      emit("ExtensionsChanged", { list: list })
      return null
    })
  }

  function setExtensionEnabled(id, on, bump) {
    if (!id || BUILTIN_EXTENSIONS.indexOf(id) < 0) {
      
      
      return Promise.reject(new Error("notFound"))
    }
    return loadEnabled()
      .then(function () {
        return loadRevisions()
      })
      .then(function () {
        enabledOverrides[id] = on === true
        if (bump) revisions[id] = extensionRevision(id) + 1
        return hostStoreSet("enabled", enabledOverrides)
      })
      .then(function () {
        return hostStoreSet("revision", revisions)
      })
      .then(function () {
        return announceExtensions()
      })
  }

  
  
  
  var rendererVersionOnce = null
  function rendererVersion() {
    if (rendererVersionOnce) return rendererVersionOnce
    rendererVersionOnce = call("runtime.getState")
      .then(function (snapshot) {
        return (snapshot && snapshot.bundledVersion) || ""
      })
      .catch(function () {
        return ""
      })
    return rendererVersionOnce
  }

  
  
  
  window.__opencodeRpcLog = []

  







  function base64ToBytes(value) {
    var binary = atob(value)
    var bytes = new Uint8Array(binary.length)
    for (var i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
    return bytes
  }

  
  
  
  
  function trace(what) {
    if (window.__hostDiagnostics !== true) return
    try {
      var text = String(what).slice(0, 900)
      fetch("/__probe/" + encodeURIComponent(text), { cache: "no-store" }).catch(function () {})
    } catch (e) {
      
    }
  }

  function call(method, payload) {
    
    
    
    var parts = method.split(".")
    var target = api
    for (var i = 0; i < parts.length; i++) {
      if (target == null) break
      target = target[parts[i]]
    }
    if (typeof target !== "function") {
      
      
      trace("rpc MISSING " + method)
      return Promise.reject(new Error("host cannot serve " + method))
    }
    try {
      
      
      trace("rpc -> " + method)
      var settled = Promise.resolve(target.call(api, payload))
      return settled.then(
        function (value) {
          trace("rpc ok " + method)
          return value
        },
        function (error) {
          trace("rpc ERR " + method + " :: " + ((error && error.message) || error))
          throw error
        },
      )
    } catch (error) {
      trace("rpc THROW " + method + " :: " + ((error && error.message) || error))
      return Promise.reject(error)
    }
  }

  

  window.electron = {
    
    windowID: "android",
    
    
    
    
    bootstrap: {},
    
    
    
    
    storageSnapshot: readStorageSnapshot(),
    
    
    getPathForFile: function () {
      return ""
    },
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    plugins: Promise.resolve([]),
  }

  
    
    function readStorageSnapshot() {
      var prefixes = Object.create(null)
      try {
        for (var i = 0; i < localStorage.length; i++) {
          var key = localStorage.key(i)
          if (key == null || key.lastIndexOf(":", 0) < 0) continue
          prefixes[key.slice(0, key.lastIndexOf(":"))] = true
        }
      } catch (e) {
        
        
      }
      var out = {}
      for (var prefix in prefixes) out[prefix] = readStore(prefix)
      return Promise.resolve(out)
    }

  
  
  
  
  
  
  
  
  
  
  
  function emptyPlugins() {
    var empty = {
      plugins: [],
      reserved: [],
      list: [],
      items: [],
      installed: [],
      available: [],
    }
    
    
    
    if (!window.__hostTracePluginSeeds) return empty
    try {
      return new Proxy(empty, {
        get: function (target, key) {
          if (typeof key === "string") trace("plugins[" + key + "]")
          return target[key]
        },
        has: function (target, key) {
          if (typeof key === "string") trace("plugins has " + key)
          return key in target
        },
        ownKeys: function (target) {
          trace("plugins keys")
          return Reflect.ownKeys(target)
        },
      })
    } catch (e) {
      return empty
    }
  }

  

  var channel = new MessageChannel()
  var port = channel.port1
  var nextRequest = 1
  var eventHandlers = Object.create(null)
  var eventStream = null

  port.onmessage = function (event) {
    var msg = event.data
    if (!msg || typeof msg !== "object") return
    if (msg._tag === "Request") return onRequest(msg)
    if (msg._tag === "Ack") return
    if (msg._tag === "Interrupt") return
  }
  port.start()

  
  
  
  
  
  
  function handOverPort() {
    window.postMessage("desktop-rpc-port", "*", [channel.port2])
  }
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", handOverPort, { once: true })
  } else {
    handOverPort()
  }

  function onRequest(msg) {
    var tag = msg.tag
    var id = msg.id
    window.__opencodeRpcLog.push({ tag: tag, payload: msg.payload === undefined ? null : msg.payload })
    if (window.__opencodeRpcLog.length > 400) window.__opencodeRpcLog.shift()

    
    
    if (tag === "DesktopEvents") {
      eventStream = id
      return
    }

    var handler = handlers[tag]
    if (!handler) {
      unknown[tag] = (unknown[tag] || 0) + 1
      if (unknown[tag] === 1) console.warn("[opencode] unhandled desktop RPC tag:", tag, msg.payload)
      return exit(id, undefined)
    }

    var result
    try {
      result = handler(msg.payload)
    } catch (error) {
      return defect(id, error)
    }
    if (result && typeof result.then === "function") {
      return result.then(
        function (value) {
          exit(id, value)
        },
        function (error) {
          defect(id, error)
        },
      )
    }
    exit(id, result)
  }

  
  
  
  
  
  
  
  
  
  window.__opencodeHostRpc = function (tag, payload) {
    var id = ++hostRpcSequence
    return new Promise(function (resolve) {
      var settled = false
      hostRpcWaiters[id] = function (msg) {
        settled = true
        resolve(msg)
      }
      onRequest({ _tag: "Request", id: id, tag: tag, payload: payload === undefined ? null : payload })
      
      
      setTimeout(function () {
        if (settled) return
        delete hostRpcWaiters[id]
        resolve({ _tag: "Timeout", tag: tag })
      }, 5000)
    })
  }

  var hostRpcSequence = 100000
  var hostRpcWaiters = Object.create(null)

  function exit(requestId, value) {
    var frame = {
      _tag: "Exit",
      requestId: requestId,
      exit: { _tag: "Success", value: serialise(value) },
    }
    settleHostRpc(requestId, frame)
    port.postMessage(frame)
  }

  function defect(requestId, error) {
    var frame = {
      _tag: "Defect",
      requestId: requestId,
      defect: { message: String((error && error.message) || error) },
    }
    settleHostRpc(requestId, frame)
    port.postMessage(frame)
  }

  
  function settleHostRpc(requestId, frame) {
    var waiter = hostRpcWaiters[requestId]
    if (!waiter) return
    delete hostRpcWaiters[requestId]
    waiter(frame)
  }

  
  
  function serialise(value) {
    if (value === undefined || value === null) return value
    if (typeof value === "function") return undefined
    try {
      structuredClone(value)
      return value
    } catch (e) {
      try {
        return JSON.parse(JSON.stringify(value))
      } catch (e2) {
        return undefined
      }
    }
  }

  

  
  
  function emit(tag, body) {
    var payload = Object.assign({ _tag: tag }, body || {})
    if (eventStream === null) {
      queued.push(payload)
      return
    }
    port.postMessage({ _tag: "Chunk", requestId: eventStream, values: [payload] })
  }

  var queued = []
  if (typeof window.__opencodeDesktopEmit === "function") return
  window.__opencodeDesktopEmit = emit

  

  
  
  
  
  
  
  
  
  
  var updaterForwarded = false
  function forwardUpdaterPluginState() {
    if (updaterForwarded) return
    updaterForwarded = true
    if (!window.api || !window.api.updater) return
    try {
      window.api.updater.subscribe(function (state) {
        emit("PluginState", { ipc: "updater", state: state })
      })
    } catch (error) {
      
    }
  }

  var handlers = {
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    



    
    
    
    AppAwaitInitialization: function () {
      return call("awaitInitialization").then(function (value) {
        return value && typeof value === "object" ? { url: value.url } : {}
      })
    },

    
    
    
    
    
    
    
    
    
    
    
    
    AppGetDefaultServerUrl: function () {
      return call("getDefaultServerUrl")
    },
    AppSetDefaultServerUrl: function (payload) {
      return call("setDefaultServerUrl", payload == null ? null : payload)
    },

    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    ExtensionList: function () {
      
      
      return Promise.all([rendererVersion(), loadEnabled(), loadRevisions()]).then(function (all) {
        var version = all[0]
        return BUILTIN_EXTENSIONS.map(function (id) {
          return {
            id: id,
            name: id,
            version: version,
            builtin: true,
            enabled: isExtensionEnabled(id),
            revision: String(extensionRevision(id)),
            error: extensionError(id),
          }
        })
      })
    },
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    ExtensionEnable: function (payload) {
      return setExtensionEnabled(payload && payload.id, true)
    },
    ExtensionDisable: function (payload) {
      return setExtensionEnabled(payload && payload.id, false)
    },
    
    ExtensionReload: function (payload) {
      return setExtensionEnabled(payload && payload.id, true, true)
    },
    
    
    
    
    
    
    
    ExtensionConfigure: function () {
      return Promise.resolve(null)
    },
    ExtensionMenubarItems: function () {
      return Promise.resolve([])
    },
    
    ExtensionSubscribe: function () {
      return Promise.resolve({ available: false })
    },
    ExtensionCapture: function () {
      return Promise.resolve(null)
    },
    ExtensionSource: function (payload) {
      return Promise.resolve(String((payload && payload.source) || ""))
    },

    
    
    AppReconnectService: function () {
      return call("awaitInitialization").then(function (value) {
        return { url: (value && value.url) || window.location.origin }
      })
    },
    
    
    
    
    
    AppConsumeInitialDeepLinks: function () {
      return Promise.resolve([])
    },

    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    AppReadyDeepLinks: function () {
      return Promise.resolve(undefined)
    },
    AppAcknowledgeDeepLinks: function () {
      return Promise.resolve(undefined)
    },

    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    AppGetSpellcheckEnabled: function () {
      
      
      return Promise.resolve(false)
    },
    AppSetSpellcheckEnabled: function () {
      return Promise.reject(
        new Error("spellcheck is not available on Android: GeckoView 150 exposes no spell checker API"),
      )
    },
    AppSetBackgroundColor: function (payload) {
      
      
      
      return call("setBackgroundColor", (payload && payload.color) || "")
    },
    AppCheckAppExists: function () {
      return Promise.resolve(false)
    },
    AppSetForceFocus: function (payload) {
      return Promise.resolve(undefined)
    },
    AppResolveAppPath: function () {
      
      return Promise.resolve(undefined)
    },
    AppSetNativeTranslations: function () {
      return Promise.resolve(undefined)
    },
    AppFinishFirstLaunchOnboarding: function () {
      return Promise.resolve(undefined)
    },
    AppIsFirstLaunchOnboardingPending: function () {
      return Promise.resolve(false)
    },
    AppRecordFatalRendererError: function (payload) {
      
      
      return Promise.resolve(undefined)
    },
    AppExportDebugLogs: function () {
      
      
      return Promise.resolve("")
    },

    
    WindowShow: function () {
      return Promise.resolve(undefined)
    },
    WindowSetFocus: function () {
      return Promise.resolve(undefined)
    },
    WindowGetFocused: function () {
      return call("getWindowFocused")
    },
    WindowGetFullscreen: function () {
      
      
      
      
      
      
      return call("getWindowFullscreen")
    },
    
    
    
    
    
    
    
    
    WindowGetZoomFactor: function () {
      return call("getZoomFactor")
    },
    WindowGetPinchZoomEnabled: function () {
      return call("getPinchZoomEnabled")
    },
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    WindowGetZoomLevel: function () {
      
      return call("getZoomFactor")
    },
    WindowSetZoomLevel: function (payload) {
      var level = payload && typeof payload === "object" ? payload.level : payload
      if (typeof level !== "number" || !isFinite(level)) return Promise.resolve(undefined)
      return call("setZoomFactor", level)
    },
    
    
    
    
    WindowRequestMicrophone: function () {
      return Promise.resolve(false)
    },
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    PluginList: function () {
      
      
      
      
      
      
      
      
      
      
      
      
      
      return Promise.resolve([
        {
          id: "ssh",
          builtin: true,
          enabled: true,
          
          
          error: "the ssh main entry runs in the desktop app; Android has no equivalent",
        },
      ])
    },
    
    
    
    
    PluginSubscribe: function (payload) {
      if (payload && payload.ipc === "updater") {
        forwardUpdaterPluginState()
        return Promise.resolve({ available: true, state: { status: "idle" } })
      }
      return Promise.resolve({ available: false })
    },
    
    
    
    PluginCall: function (payload) {
      if (!payload || payload.ipc !== "updater") return Promise.resolve(null)

      if (payload.method === "check") return window.api.updater.check()

      if (payload.method === "install") {
        return window.api.updater.install().then(function () {
          return null
        })
      }

      return Promise.resolve(null)
    },

    
    
    
    
    
    
    
    

    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    MenuRunAction: function (payload) {
      var action = payload && payload.action

      if (typeof action !== "string" || !action) return Promise.resolve(undefined)
      
      var command = EDIT_COMMANDS[action]
      if (command) {
        try {
          document.execCommand(command)
        } catch (error) {
          
        }
        return Promise.resolve(undefined)
      }
      return call("runDesktopMenuAction", action)
    },

    
    FilesWriteClipboardText: function (payload) {
      var text = payload && typeof payload.text === "string" ? payload.text : ""
      try {
        if (navigator.clipboard && navigator.clipboard.writeText) return navigator.clipboard.writeText(text)
      } catch (error) {
        return Promise.reject(error)
      }
      
      
      try {
        var area = document.createElement("textarea")
        area.value = text
        area.setAttribute("readonly", "")
        area.style.position = "fixed"
        area.style.opacity = "0"
        document.body.appendChild(area)
        area.select()
        document.execCommand("copy")
        document.body.removeChild(area)
        return Promise.resolve()
      } catch (error) {
        return Promise.reject(error)
      }
    },

    
    
    FilesOpenBrowser: function (payload) {
      var url = payload && (payload.url || payload.href)
      if (url) {
        try {
          window.open(url, "_blank", "noopener")
        } catch (error) {
          
        }
      }
      return Promise.resolve()
    },
    FilesOpenPath: function () {
      
      
      return Promise.resolve(false)
    },
    FilesReadPickedFile: function () {
      
      
      return Promise.resolve(null)
    },
    FilesReleasePickedFiles: function () {
      return Promise.resolve()
    },

    
    
    
    
    DraftsSet: function (payload) {
      try {
        var key = "opencode.draft:" + (payload && payload.key)
        localStorage.setItem(key, JSON.stringify(payload && payload.value))
      } catch (error) {
        
      }
      return Promise.resolve()
    },
    DraftsGet: function (payload) {
      try {
        var raw = localStorage.getItem("opencode.draft:" + (payload && payload.key))
        return Promise.resolve(raw === null ? null : JSON.parse(raw))
      } catch (error) {
        return Promise.resolve(null)
      }
    },
    DraftsDelete: function (payload) {
      try {
        localStorage.removeItem("opencode.draft:" + (payload && payload.key))
      } catch (error) {
        
      }
      return Promise.resolve()
    },
    DraftsPutBlob: function () {
      
      
      return Promise.resolve(null)
    },
    DraftsGetBlob: function () {
      return Promise.resolve(null)
    },

    
    
    PluginEmbed: function () {
      return Promise.resolve()
    },
    PluginCapture: function () {
      return Promise.resolve(null)
    },
    PluginMenubarItem: function () {
      return Promise.resolve()
    },
    PluginConfigure: function () {
      
      
      
      
      
      
      return Promise.resolve()
    },

    
    WindowSetTrafficLightsPosition: function () {
      return Promise.resolve()
    },
    WindowSetVoiceCallActive: function () {
      return Promise.resolve()
    },
    PluginGuis: function () {
      
      var empty = emptyPlugins()
      return Promise.resolve({ plugins: empty.plugins, reserved: empty.reserved })
    },
    PluginMenubarItems: function () {
      
      return Promise.resolve([])
    },
    PluginEnable: function () {
      return Promise.reject(new Error("plugins are not available on Android"))
    },
    PluginDisable: function () {
      return Promise.reject(new Error("plugins are not available on Android"))
    },
    PluginReload: function () {
      return Promise.reject(new Error("plugins are not available on Android"))
    },
    
    
    WindowSetPinchZoomEnabled: function (payload) {
      return call("setPinchZoomEnabled", !!(payload && payload.enabled))
    },
    WindowSetTitlebar: function () {
      return Promise.resolve(undefined)
    },
    WindowThemeReady: function () {
      return Promise.resolve(undefined)
    },
    
    AppRelaunch: function () {
      return call("relaunch").catch(function () {})
    },
    FilesOpenExternal: function (payload) {
      return call("openExternal", (payload && payload.url) || "").catch(function () {})
    },
    FilesOpenLocalFile: function (payload) {
      return call("openLocalFile", { url: (payload && payload.url) || "" }).catch(function () {})
    },

    
    WindowSetZoomFactor: function (payload) {
      var factor = payload && typeof payload === "object" ? payload.factor : payload
      if (typeof factor !== "number" || !isFinite(factor)) return Promise.resolve(undefined)
      return call("setZoomFactor", factor)
    },

    
    
    
    
    FilesOpenDirectoryPicker: function (payload) {
      var options = (payload && payload.options) || {}
      return call("openDirectoryPicker", { options: options }).then(function (paths) {
        if (!paths || !paths.length) return null
        
        return options.multiple ? paths : paths[0]
      })
    },
    FilesOpenFilePicker: function (payload) {
      var options = (payload && payload.options) || {}
      return call("openFilePicker", { options: options }).then(function (picked) {
        if (!picked) return null
        return { token: picked.token, files: picked.files || [] }
      })
    },
    FilesSaveFile: function () {
      
      
      return Promise.resolve(false)
    },
    
    
    
    
    
    
    
    
    
    
    
    
    FilesReadClipboardImage: function () {
      return call("readClipboardImage").then(function (image) {
        if (!image || typeof image.buffer !== "string") return null
        return { buffer: base64ToBytes(image.buffer), width: image.width, height: image.height }
      })
    },
    FilesRevealPath: function () {
      
      return Promise.resolve(false)
    },

    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    StorageItems: function (payload) {
      return Promise.resolve(readStore((payload && payload.name) || ""))
    },
    StorageUpdate: function (payload) {
      var name = (payload && payload.name) || ""
      var insert = (payload && payload.insert) || {}
      var remove = payload && payload.remove
      
      
      if (remove) {
        for (var i = 0; i < remove.length; i++) removeItem(name, remove[i])
      }
      for (var key in insert) {
        if (Object.prototype.hasOwnProperty.call(insert, key)) writeItem(name, key, insert[key])
      }
      return Promise.resolve(bumpRevision(name))
    },
    StorageClear: function (payload) {
      var name = (payload && payload.name) || ""
      prefixKeys(name).forEach(function (key) {
        removeItem(name, key)
      })
      bumpRevision(name)
      return Promise.resolve(undefined)
    },
  }

  var REVISION_PREFIX = "__opencode.revision."

  function revisionKey(prefix) {
    return REVISION_PREFIX + prefix
  }

  function currentRevision(prefix) {
    var raw = null
    try {
      raw = localStorage.getItem(revisionKey(prefix))
    } catch (e) {
      return 0
    }
    var value = Number(raw)
    return isFinite(value) ? value : 0
  }

  function bumpRevision(prefix) {
    var next = currentRevision(prefix) + 1
    try {
      localStorage.setItem(revisionKey(prefix), String(next))
    } catch (e) {
      
    }
    return next
  }

  
  function prefixKeys(prefix) {
    var out = []
    if (!prefix) return out
    var head = prefix + ":"
    try {
      for (var i = 0; i < localStorage.length; i++) {
        var key = localStorage.key(i)
        if (key != null && key.lastIndexOf(head, 0) === 0) out.push(key.slice(head.length))
      }
    } catch (e) {
      
    }
    return out
  }

  










  function readStore(prefix) {
    var record = { items: {}, revision: currentRevision(prefix) }
    prefixKeys(prefix).forEach(function (item) {
      var raw = null
      try {
        raw = localStorage.getItem(prefix + ":" + item)
      } catch (e) {
        return
      }
      if (raw == null) return
      record.items[item] = raw
    })
    return record
  }

  





  function writeItem(prefix, item, value) {
    try {
      localStorage.setItem(prefix + ":" + item, typeof value === "string" ? value : JSON.stringify(value))
    } catch (e) {
      
    }
  }

  function removeItem(prefix, item) {
    try {
      localStorage.removeItem(prefix + ":" + item)
    } catch (e) {
      
    }
  }

  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  var sidecarOrigin = null
  var sidecarAuthorization = null

  function refreshSidecarAuthorization() {
    return Promise.all([
      call("awaitInitialization").catch(function () {
        return null
      }),
      call("sidecarAuthorization").catch(function () {
        return null
      }),
    ]).then(function (pair) {
      var init = pair[0]
      sidecarOrigin = init && typeof init === "object" && init.url ? String(init.url) : null
      sidecarAuthorization = typeof pair[1] === "string" && pair[1] ? pair[1] : null
    })
  }

  function withSidecarAuthorization(input, init) {
    if (!sidecarAuthorization || !sidecarOrigin) return null
    var url
    try {
      url = typeof input === "string" || input instanceof URL ? String(input) : input && input.url
      if (!url) return null
      
      
      if (new URL(url, window.location.href).origin !== sidecarOrigin) return null
      if (init && hasHeader(init.headers)) return null
      if (typeof Request === "function" && input instanceof Request && hasHeader(input.headers)) return null
    } catch (e) {
      return null
    }
    var next = {}
    for (var key in init) next[key] = init[key]
    next.headers = withHeader(init && init.headers, "Authorization", sidecarAuthorization)
    return next
  }

  
  function hasHeader(headers) {
    if (!headers) return false
    if (typeof headers.has === "function") return headers.has("Authorization")
    return Object.keys(headers).some(function (key) {
      return String(key).toLowerCase() === "authorization"
    })
  }

  function withHeader(headers, name, value) {
    var out = {}
    if (headers && typeof headers.forEach === "function" && !Array.isArray(headers)) {
      headers.forEach(function (v, k) {
        out[k] = v
      })
    } else if (Array.isArray(headers)) {
      headers.forEach(function (pair) {
        out[pair[0]] = pair[1]
      })
    } else if (headers) {
      for (var key in headers) out[key] = headers[key]
    }
    out[name] = value
    return out
  }

  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  var hostOrigin = window.location.origin

  










  function isForeignLoopback(parsed) {
    if (parsed.hostname !== "127.0.0.1" && parsed.hostname !== "localhost") return false
    return parsed.origin !== hostOrigin
  }

  
  function rewriteToHost(url) {
    try {
      var parsed = new URL(String(url), window.location.href)
      if (!isForeignLoopback(parsed)) return null
      
      
      return hostOrigin + parsed.pathname + parsed.search + parsed.hash
    } catch (e) {
      return null
    }
  }

  
  function rewriteWebSocket(url) {
    try {
      var parsed = new URL(String(url), window.location.href)
      if (!isForeignLoopback(parsed)) return null
      var scheme = parsed.protocol === "wss:" ? "wss:" : "ws:"
      var host = new URL(hostOrigin)
      return scheme + "//" + host.host + parsed.pathname + parsed.search
    } catch (e) {
      return null
    }
  }

  var nativeFetch = window.fetch
  window.fetch = function (input, init) {
    var target = typeof input === "string" || input instanceof URL ? String(input) : input && input.url
    var rewritten = target ? rewriteToHost(target) : null
    try {
      if (target && String(target).indexOf("prompt") >= 0) {
        console.log("PROMPT_FETCH url=" + String(target).slice(0,140) + " rewritten=" + (rewritten ? String(rewritten).slice(0,140) : "NO"))
      }
    } catch (traceError) {}

    if (rewritten) {
      
      
      
      
      
      if (typeof Request === "function" && input instanceof Request) {
        try {
          return nativeFetch.call(window, new Request(rewritten, input))
        } catch (error) {
          
          
          return nativeFetch.call(window, rewritten, init)
        }
      }
      
      
      var next = {}
      for (var key in init) next[key] = init[key]
      return nativeFetch.call(window, rewritten, next)
    }
    var withAuth = withSidecarAuthorization(input, init)
    return nativeFetch.call(window, input, withAuth || init)
  }

  
  
  
  
  var NativeXHR = window.XMLHttpRequest
  if (NativeXHR && NativeXHR.prototype && NativeXHR.prototype.open) {
    var nativeXhrOpen = NativeXHR.prototype.open
    NativeXHR.prototype.open = function (method, url) {
      var target = rewriteToHost(url)
      var args = Array.prototype.slice.call(arguments)
      if (target) args[1] = target
      return nativeXhrOpen.apply(this, args)
    }
  }

  
  
  
  var NativeWebSocket = window.WebSocket
  if (typeof NativeWebSocket === "function") {
    var RewritingWebSocket = function (url, protocols) {
      var target = rewriteWebSocket(url)
      var finalUrl = target || url
      return arguments.length > 1 && protocols !== undefined
        ? new NativeWebSocket(finalUrl, protocols)
        : new NativeWebSocket(finalUrl)
    }
    RewritingWebSocket.prototype = NativeWebSocket.prototype
    ;["CONNECTING", "OPEN", "CLOSING", "CLOSED"].forEach(function (name) {
      RewritingWebSocket[name] = NativeWebSocket[name]
    })
    window.WebSocket = RewritingWebSocket
  }

  
  
  refreshSidecarAuthorization()
  window.__opencodeSidecarAuthorization = function () {
    return { origin: sidecarOrigin, authorization: sidecarAuthorization }
  }

  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
})()
