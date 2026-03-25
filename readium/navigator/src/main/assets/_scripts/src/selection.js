//
//  Copyright 2021 Readium Foundation. All rights reserved.
//  Use of this source code is governed by the BSD-style license
//  available in the top-level LICENSE file of the project.
//

import { log as logNative, logError, snapCurrentOffset } from "./utils";
import { toNativeRect } from "./rect";
import { TextRange } from "./vendor/hypothesis/anchoring/text-range";

// Polyfill for Android API 26
import matchAll from "string.prototype.matchall";
matchAll.shim();

const debug = true;

// Notify native code that the selection changes.
window.addEventListener(
  "load",
  function () {
    var isSelecting = false;
    var snapTimeout = null;
    // In paginated mode, the WebView auto-scrolls horizontally to follow the
    // selection handle. This shifts CSS columns and causes the selection to
    // flicker between two positions. Lock scrollLeft while selecting.
    var lockedScrollLeft = null;
    var selectionRAF = null;
    // Track the last valid selection range so we can restore it when the
    // Android WebView glitches across CSS column boundaries (focus jumps
    // from a #text node to a container element, selecting a huge chunk).
    var lastValidRange = null;
    var lastValidTextLen = 0;
    // When true, selectionchange events are suppressed (used during range restoration
    // to prevent removeAllRanges/addRange from triggering onSelectionEnd/onSelectionStart).
    var suppressSelectionChange = false;

    // requestAnimationFrame loop: resets scrollLeft before every paint frame.
    // This is the only reliable way to prevent the CSS column scroll from
    // rendering the wrong column for even a single frame.
    function lockScrollFrame() {
      if (isSelecting && lockedScrollLeft !== null && document.scrollingElement) {
        if (document.scrollingElement.scrollLeft !== lockedScrollLeft) {
          document.scrollingElement.scrollLeft = lockedScrollLeft;
        }
      }
      if (isSelecting) {
        selectionRAF = requestAnimationFrame(lockScrollFrame);
      } else {
        selectionRAF = null;
      }
    }

    // Also catch scroll events as backup.
    document.addEventListener(
      "scroll",
      function () {
        if (isSelecting && lockedScrollLeft !== null && document.scrollingElement) {
          document.scrollingElement.scrollLeft = lockedScrollLeft;
        }
      },
      { passive: false }
    );

    document.addEventListener("selectionchange", function () {
      if (suppressSelectionChange) return;
      var sel = window.getSelection();
      var collapsed = sel.isCollapsed;
      var focusType = sel.focusNode ? sel.focusNode.nodeType : -1;
      var focusTag = sel.focusNode ? (sel.focusNode.nodeName || sel.focusNode.parentElement?.tagName || "?") : "null";
      var textLen = collapsed ? 0 : sel.toString().length;
      Android.log("[SEL] collapsed=" + collapsed + " textLen=" + textLen +
        " focus=" + focusTag + "(type=" + focusType + ")" +
        " lastValid=" + lastValidTextLen +
        " scrollLeft=" + (document.scrollingElement ? document.scrollingElement.scrollLeft : -1));

      // Detect and suppress column-crossing glitch: when dragging a selection
      // handle across a CSS column boundary, Android WebView may momentarily
      // snap the focus node to a container element (div/body), causing a huge
      // text selection spike. When this happens, restore the last valid range.
      if (isSelecting && !collapsed && sel.focusNode && sel.focusNode.nodeType !== Node.TEXT_NODE) {
        var currentLen = sel.toString().length;
        Android.log("[SEL] SPIKE detected: focusType=" + focusType + " currentLen=" + currentLen + " lastValid=" + lastValidTextLen);
        // A genuine user drag grows gradually; a spike jumps by hundreds of chars.
        if (lastValidRange && currentLen > lastValidTextLen * 3 && currentLen - lastValidTextLen > 200) {
          Android.log("[SEL] RESTORING last valid range (len=" + lastValidTextLen + ")");
          try {
            suppressSelectionChange = true;
            window.__suppressRNSelectionEvents = true;
            sel.removeAllRanges();
            sel.addRange(lastValidRange.cloneRange());
          } catch (e) {
            Android.log("[SEL] RESTORE FAILED: " + e);
          } finally {
            // Use setTimeout to release suppression after the synchronous
            // selectionchange events triggered by removeAllRanges/addRange.
            setTimeout(function () {
              suppressSelectionChange = false;
              window.__suppressRNSelectionEvents = false;
            }, 0);
          }
          return; // Skip further processing — we restored the old selection.
        }
      }

      // Save the current range as "last valid" when the focus is on a text node.
      if (!collapsed && sel.rangeCount > 0 && sel.focusNode && sel.focusNode.nodeType === Node.TEXT_NODE) {
        try {
          lastValidRange = sel.getRangeAt(0).cloneRange();
          lastValidTextLen = sel.toString().length;
        } catch (e) { /* ignore */ }
      }

      // Reset scrollLeft immediately at the top of every selectionchange event.
      if (isSelecting && lockedScrollLeft !== null && document.scrollingElement) {
        document.scrollingElement.scrollLeft = lockedScrollLeft;
      }

      if (collapsed && isSelecting) {
        isSelecting = false;
        lockedScrollLeft = null;
        lastValidRange = null;
        lastValidTextLen = 0;
        if (selectionRAF) { cancelAnimationFrame(selectionRAF); selectionRAF = null; }
        Android.onSelectionEnd();
        // Debounce snap to avoid disrupting selection handle adjustment if the
        // collapse was spurious (selection re-expands within 300ms).
        if (snapTimeout) clearTimeout(snapTimeout);
        snapTimeout = setTimeout(function () {
          snapCurrentOffset();
          snapTimeout = null;
        }, 300);
      } else if (!collapsed && !isSelecting) {
        isSelecting = true;
        // Lock horizontal scroll position at the moment selection starts
        // and begin rAF loop to enforce it every frame.
        if (document.scrollingElement) {
          lockedScrollLeft = document.scrollingElement.scrollLeft;
        }
        if (!selectionRAF) {
          selectionRAF = requestAnimationFrame(lockScrollFrame);
        }
        Android.onSelectionStart();
        // Cancel pending snap — user resumed selecting before the debounce fired.
        if (snapTimeout) {
          clearTimeout(snapTimeout);
          snapTimeout = null;
        }
      }
    });
  },
  false
);

export function getCurrentSelection() {
  const text = getCurrentSelectionText();
  if (!text) {
    return null;
  }
  const rect = getSelectionRect();
  return { text, rect };
}

function getSelectionRect() {
  try {
    let sel = window.getSelection();
    if (!sel) {
      return;
    }
    let range = sel.getRangeAt(0);

    return toNativeRect(range.getBoundingClientRect());
  } catch (e) {
    logError(e);
    return null;
  }
}

function getCurrentSelectionText() {
  const selection = window.getSelection();
  if (!selection) {
    return undefined;
  }
  if (selection.isCollapsed) {
    return undefined;
  }
  const highlight = selection.toString();
  const cleanHighlight = highlight
    .trim()
    .replace(/\n/g, " ")
    .replace(/\s\s+/g, " ");
  if (cleanHighlight.length === 0) {
    return undefined;
  }
  if (!selection.anchorNode || !selection.focusNode) {
    return undefined;
  }
  const range =
    selection.rangeCount === 1
      ? selection.getRangeAt(0)
      : createOrderedRange(
          selection.anchorNode,
          selection.anchorOffset,
          selection.focusNode,
          selection.focusOffset
        );
  if (!range || range.collapsed) {
    log("$$$$$$$$$$$$$$$$$ CANNOT GET NON-COLLAPSED SELECTION RANGE?!");
    return undefined;
  }

  const text = document.body.textContent;
  const textRange = TextRange.fromRange(range).relativeTo(document.body);
  const start = textRange.start.offset;
  const end = textRange.end.offset;

  const snippetLength = 200;

  // Compute the text before the highlight, ignoring the first "word", which might be cut.
  let before = text.slice(Math.max(0, start - snippetLength), start);
  let firstWordStart = before.search(/\P{L}\p{L}/gu);
  if (firstWordStart !== -1) {
    before = before.slice(firstWordStart + 1);
  }

  // Compute the text after the highlight, ignoring the last "word", which might be cut.
  let after = text.slice(end, Math.min(text.length, end + snippetLength));
  let lastWordEnd = Array.from(after.matchAll(/\p{L}\P{L}/gu)).pop();
  if (lastWordEnd !== undefined && lastWordEnd.index > 1) {
    after = after.slice(0, lastWordEnd.index + 1);
  }

  return { highlight, before, after };
}

function createOrderedRange(startNode, startOffset, endNode, endOffset) {
  const range = new Range();
  range.setStart(startNode, startOffset);
  range.setEnd(endNode, endOffset);
  if (!range.collapsed) {
    return range;
  }
  log(">>> createOrderedRange COLLAPSED ... RANGE REVERSE?");
  const rangeReverse = new Range();
  rangeReverse.setStart(endNode, endOffset);
  rangeReverse.setEnd(startNode, startOffset);
  if (!rangeReverse.collapsed) {
    log(">>> createOrderedRange RANGE REVERSE OK.");
    return range;
  }
  log(">>> createOrderedRange RANGE REVERSE ALSO COLLAPSED?!");
  return undefined;
}

export function convertRangeInfo(document, rangeInfo) {
  const startElement = document.querySelector(
    rangeInfo.startContainerElementCssSelector
  );
  if (!startElement) {
    log("^^^ convertRangeInfo NO START ELEMENT CSS SELECTOR?!");
    return undefined;
  }
  let startContainer = startElement;
  if (rangeInfo.startContainerChildTextNodeIndex >= 0) {
    if (
      rangeInfo.startContainerChildTextNodeIndex >=
      startElement.childNodes.length
    ) {
      log(
        "^^^ convertRangeInfo rangeInfo.startContainerChildTextNodeIndex >= startElement.childNodes.length?!"
      );
      return undefined;
    }
    startContainer =
      startElement.childNodes[rangeInfo.startContainerChildTextNodeIndex];
    if (startContainer.nodeType !== Node.TEXT_NODE) {
      log("^^^ convertRangeInfo startContainer.nodeType !== Node.TEXT_NODE?!");
      return undefined;
    }
  }
  const endElement = document.querySelector(
    rangeInfo.endContainerElementCssSelector
  );
  if (!endElement) {
    log("^^^ convertRangeInfo NO END ELEMENT CSS SELECTOR?!");
    return undefined;
  }
  let endContainer = endElement;
  if (rangeInfo.endContainerChildTextNodeIndex >= 0) {
    if (
      rangeInfo.endContainerChildTextNodeIndex >= endElement.childNodes.length
    ) {
      log(
        "^^^ convertRangeInfo rangeInfo.endContainerChildTextNodeIndex >= endElement.childNodes.length?!"
      );
      return undefined;
    }
    endContainer =
      endElement.childNodes[rangeInfo.endContainerChildTextNodeIndex];
    if (endContainer.nodeType !== Node.TEXT_NODE) {
      log("^^^ convertRangeInfo endContainer.nodeType !== Node.TEXT_NODE?!");
      return undefined;
    }
  }
  return createOrderedRange(
    startContainer,
    rangeInfo.startOffset,
    endContainer,
    rangeInfo.endOffset
  );
}

export function location2RangeInfo(location) {
  const locations = location.locations;
  const domRange = locations.domRange;
  const start = domRange.start;
  const end = domRange.end;

  return {
    endContainerChildTextNodeIndex: end.textNodeIndex,
    endContainerElementCssSelector: end.cssSelector,
    endOffset: end.offset,
    startContainerChildTextNodeIndex: start.textNodeIndex,
    startContainerElementCssSelector: start.cssSelector,
    startOffset: start.offset,
  };
}

function log() {
  if (debug) {
    logNative.apply(null, arguments);
  }
}
