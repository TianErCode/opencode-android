


















































;
(function () {
  "use strict"

  if (window.__opencodeTouchHoverDismiss) return
  window.__opencodeTouchHoverDismiss = true

  var LAYER_SELECTOR = [
    '[data-component="menu-v2-content"]',
    '[data-component="popover-content"]',
    '[data-component="tooltip"]',
    '[data-component="tooltip-v2"]',
    '[data-component="session-tab-popover"]',
  ].join(",")

  var SUB_CONTENT_SELECTOR = [
    '[data-slot="dropdown-menu-sub-content"]',
    '[data-component="menu-v2-content"]',
  ].join(",")

  var SUB_TRIGGER_SELECTOR = [
    '[data-slot="dropdown-menu-sub-trigger"]',
    '[data-component="menu-v2-item"][aria-haspopup="true"]',
  ].join(",")

  var TOOLTIP_TRIGGER_SELECTOR = '[data-component="tooltip-trigger"],[data-component="tooltip-v2-trigger"]'
  var HOVER_CARD_TRIGGER_SELECTOR = '[data-component="session-tab-popover-trigger"]'
  var MENU_VALUE_TRIGGER_SELECTOR = "[data-kb-menu-value-trigger]"

  






  function isTouchInput(pointerType, firesTouchEvents) {
    return pointerType === "touch" || firesTouchEvents === true
  }

  
  function shouldReplay(visibleLayerCount) {
    return visibleLayerCount > 0
  }

  function isVisible(element) {
    if (!element || typeof element.getClientRects !== "function") return false
    try {
      return element.getClientRects().length > 0
    } catch (error) {
      return false
    }
  }

  function contains(container, node) {
    if (!container || !node) return false
    if (container === node) return true
    try {
      return typeof container.contains === "function" && container.contains(node)
    } catch (error) {
      return false
    }
  }

  function queryAll(document, selector) {
    try {
      var nodes = document.querySelectorAll(selector)
      var found = []
      for (var i = 0; i < nodes.length; i += 1) found.push(nodes[i])
      return found
    } catch (error) {
      return []
    }
  }

  function queryVisible(document, selector) {
    var nodes = queryAll(document, selector)
    var visible = []
    for (var i = 0; i < nodes.length; i += 1) {
      if (isVisible(nodes[i])) visible.push(nodes[i])
    }
    return visible
  }

  function getAttribute(element, name) {
    try {
      return typeof element.getAttribute === "function" ? element.getAttribute(name) : null
    } catch (error) {
      return null
    }
  }

  function hasClass(element, name) {
    try {
      return !!element.classList && element.classList.contains(name)
    } catch (error) {
      return false
    }
  }

  







  function isSubmenuContent(element, document) {
    if (getAttribute(element, "data-slot") === "dropdown-menu-sub-content") return true
    if (hasClass(element, "desktop-app-menu-sub")) return true
    var labelledBy = getAttribute(element, "aria-labelledby")
    if (!labelledBy) return false
    var trigger = document.getElementById ? document.getElementById(labelledBy) : null
    return !!trigger && getAttribute(trigger, "role") === "menuitem"
  }

  
  function submenuTriggerOf(content, document) {
    var labelledBy = getAttribute(content, "aria-labelledby")
    if (!labelledBy || !document.getElementById) return null
    return document.getElementById(labelledBy)
  }

  





  function staleSubmenus(document, target) {
    var contents = queryVisible(document, SUB_CONTENT_SELECTOR)
    var stale = []
    for (var i = 0; i < contents.length; i += 1) {
      var content = contents[i]
      if (!isSubmenuContent(content, document)) continue
      if (contains(content, target)) continue
      var trigger = submenuTriggerOf(content, document)
      if (contains(trigger, target)) continue
      stale.push({ content: content, trigger: trigger })
    }
    return stale
  }

  function makePointerEvent(type, init) {
    if (typeof window.PointerEvent === "function") return new window.PointerEvent(type, init)
    
    
    var event = new window.MouseEvent(type, init)
    try {
      Object.defineProperty(event, "pointerType", { value: init.pointerType })
    } catch (error) {
      
    }
    return event
  }

  function pointerInit(x, y) {
    return {
      bubbles: true,
      cancelable: true,
      composed: true,
      isPrimary: true,
      pointerId: 1,
      pointerType: "mouse",
      button: 0,
      buttons: 0,
      clientX: x || 0,
      clientY: y || 0,
    }
  }

  function dispatchPointer(element, type, init) {
    if (!element || typeof element.dispatchEvent !== "function") return false
    try {
      element.dispatchEvent(makePointerEvent(type, init))
      return true
    } catch (error) {
      return false
    }
  }

  
  function replayHoverMove(target, x, y) {
    dispatchPointer(target, "pointermove", pointerInit(x, y))
  }

  







  function switchMenuBar(trigger, x, y) {
    if (!trigger || !trigger.dataset) return false
    var previous = trigger.dataset.pointerType
    if (previous === "mouse") return false
    trigger.dataset.pointerType = "mouse"
    try {
      var event = new window.MouseEvent("mouseover", {
        bubbles: true,
        cancelable: true,
        composed: true,
        clientX: x || 0,
        clientY: y || 0,
      })
      trigger.dispatchEvent(event)
    } finally {
      if (previous === undefined) {
        delete trigger.dataset.pointerType
      } else {
        trigger.dataset.pointerType = previous
      }
    }
    return true
  }

  
  function isRtl(element) {
    try {
      var style = window.getComputedStyle && window.getComputedStyle(element)
      if (style && style.direction === "rtl") return true
    } catch (error) {
      
    }
    var root = document.documentElement
    return !!root && (getAttribute(root, "dir") === "rtl" || getAttribute(root, "direction") === "rtl")
  }

  






  function submenuCloseKey(content) {
    var orientation = getAttribute(content, "data-orientation")
    var rtl = isRtl(content)
    if (orientation === "horizontal") return rtl ? "ArrowRight" : "ArrowLeft"
    return rtl ? "ArrowDown" : "ArrowUp"
  }

  function dispatchKey(element, key) {
    if (!element || typeof element.dispatchEvent !== "function") return false
    try {
      var init = { key: key, code: key, bubbles: true, cancelable: true, composed: true }
      var event
      if (typeof window.KeyboardEvent === "function") {
        event = new window.KeyboardEvent("keydown", init)
      } else {
        event = new window.Event("keydown", { bubbles: true, cancelable: true, composed: true })
        try {
          Object.defineProperty(event, "key", { value: key })
        } catch (error) {
          
        }
      }
      element.dispatchEvent(event)
      return true
    } catch (error) {
      return false
    }
  }

  







  function dismissSubmenu(stale, x, y) {
    if (!stale || !stale.content) return false
    if (stale.trigger) dispatchPointer(stale.trigger, "pointerleave", pointerInit(x, y))
    dispatchPointer(stale.content, "pointerleave", pointerInit(x, y))
    return dispatchKey(stale.content, submenuCloseKey(stale.content))
  }

  





  function dismissHoverLayers(document) {
    var triggers = queryVisible(document, TOOLTIP_TRIGGER_SELECTOR).concat(
      queryVisible(document, HOVER_CARD_TRIGGER_SELECTOR),
    )
    for (var i = 0; i < triggers.length; i += 1) {
      dispatchPointer(triggers[i], "pointerleave", pointerInit(0, 0))
    }
  }

  function closeStaleSubmenus(document, target, x, y) {
    var stale = staleSubmenus(document, target)
    for (var i = 0; i < stale.length; i += 1) dismissSubmenu(stale[i], x, y)
    return stale.length
  }

  






  function onTouchPointerDown(target, x, y, document) {
    if (!target || typeof target.dispatchEvent !== "function") return false
    var layers = queryVisible(document, LAYER_SELECTOR)
    if (!shouldReplay(layers.length)) return false

    var trigger = null
    try {
      trigger = target.closest ? target.closest(MENU_VALUE_TRIGGER_SELECTOR) : null
    } catch (error) {
      trigger = null
    }
    if (trigger) switchMenuBar(trigger, x, y)

    closeStaleSubmenus(document, target, x, y)
    replayHoverMove(target, x, y)
    dismissHoverLayers(document)
    schedulePostTapCleanup(target, x, y)
    return true
  }

  




  function schedulePostTapCleanup(target, x, y) {
    if (typeof window.setTimeout !== "function") return
    window.setTimeout(function () {
      try {
        closeStaleSubmenus(document, target, x, y)
      } catch (error) {
        
      }
    }, 0)
  }

  function handlePointerDown(event) {
    try {
      if (!event || event.defaultPrevented) return
      var capabilities = event.sourceCapabilities
      if (!isTouchInput(event.pointerType, capabilities && capabilities.firesTouchEvents)) return
      onTouchPointerDown(event.target, event.clientX, event.clientY, document)
    } catch (error) {
      
    }
  }

  window.addEventListener("pointerdown", handlePointerDown, { capture: true, passive: true })

  if (window.__opencodeTouchHoverTest) {
    window.__opencodeTouchHoverApi = {
      isTouchInput: isTouchInput,
      shouldReplay: shouldReplay,
      onTouchPointerDown: onTouchPointerDown,
      isSubmenuContent: isSubmenuContent,
      submenuCloseKey: submenuCloseKey,
      staleSubmenus: staleSubmenus,
      selectors: {
        layer: LAYER_SELECTOR,
        subContent: SUB_CONTENT_SELECTOR,
        subTrigger: SUB_TRIGGER_SELECTOR,
        tooltipTrigger: TOOLTIP_TRIGGER_SELECTOR,
        hoverCardTrigger: HOVER_CARD_TRIGGER_SELECTOR,
        menuValueTrigger: MENU_VALUE_TRIGGER_SELECTOR,
      },
    }
  }
})()
