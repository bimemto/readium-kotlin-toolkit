//
//  Copyright 2022 Readium Foundation. All rights reserved.
//  Use of this source code is governed by the BSD-style license
//  available in the top-level LICENSE file of the project.
//

import { isScrollModeEnabled } from "./utils";
import { getCssSelector } from "css-selector-generator";

// See. https://github.com/JayPanoz/architecture/tree/touch-handling/misc/touch-handling
export function nearestInteractiveElement(element) {
  if (element == null) {
    return null;
  }
  var interactiveTags = [
    "a",
    "audio",
    "button",
    "canvas",
    "details",
    "input",
    "label",
    "option",
    "select",
    "submit",
    "textarea",
    "video",
  ];
  if (interactiveTags.indexOf(element.nodeName.toLowerCase()) != -1) {
    return element.outerHTML;
  }

  // Checks whether the element is editable by the user.
  if (
    element.hasAttribute("contenteditable") &&
    element.getAttribute("contenteditable").toLowerCase() != "false"
  ) {
    return element.outerHTML;
  }

  // Checks parents recursively because the touch might be for example on an <em> inside a <a>.
  if (element.parentElement) {
    return nearestInteractiveElement(element.parentElement);
  }

  return null;
}

export function findFirstVisibleLocator() {
  const element = findElement(document.body);

  // Get text actually visible at the top of the viewport
  // instead of full textContent of the nearest block element
  var visibleText = getVisibleTextAtViewportTop();
  if (!visibleText) {
    visibleText = element.textContent;
  }

  return {
    href: "#",
    type: "application/xhtml+xml",
    locations: {
      cssSelector: getCssSelector(element),
    },
    text: {
      highlight: visibleText,
    },
  };
}

/**
 * Get text visible at the top of the viewport using caretRangeFromPoint.
 * Returns the first ~100 chars of visible text starting from the exact
 * viewport top position, not from the beginning of the paragraph.
 */
function getVisibleTextAtViewportTop() {
  // Scan from top of viewport downward to find actual text
  for (var y = 0; y < window.innerHeight * 0.3; y += 5) {
    // First probe at center to check if this y has text
    var centerRange = document.caretRangeFromPoint(window.innerWidth / 2, y);
    if (!centerRange || !centerRange.startContainer) continue;
    if (centerRange.startContainer.nodeType !== Node.TEXT_NODE) continue;
    if (!centerRange.startContainer.textContent || !centerRange.startContainer.textContent.trim()) continue;

    // Found a line with text — now probe from left edge to get start of line
    var range = null;
    for (var x = 1; x < window.innerWidth / 2; x += 5) {
      var r = document.caretRangeFromPoint(x, y);
      if (r && r.startContainer && r.startContainer.nodeType === Node.TEXT_NODE &&
          r.startContainer.textContent && r.startContainer.textContent.trim()) {
        range = r;
        break;
      }
    }
    if (!range) range = centerRange;

    var node = range.startContainer;
    // Get text from the exact caret position (not from start of paragraph)
    var text = node.textContent.substring(range.startOffset).trim();

    // Collect more text from subsequent text nodes if needed
    if (text.length < 100) {
      var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
      var foundCurrent = false;
      while (walker.nextNode()) {
        if (walker.currentNode === node) {
          foundCurrent = true;
          continue;
        }
        if (foundCurrent && walker.currentNode.textContent.trim()) {
          text += ' ' + walker.currentNode.textContent.trim();
          if (text.length >= 100) break;
        }
      }
    }

    text = text.trim();
    if (text) return text;
  }

  return null;
}

function findElement(rootElement) {
  for (var i = 0; i < rootElement.children.length; i++) {
    const child = rootElement.children[i];
    if (!shouldIgnoreElement(child) && isElementVisible(child)) {
      return findElement(child);
    }
  }
  return rootElement;
}

function isElementVisible(element) {
  if (readium.isFixedLayout) return true;

  if (element === document.body || element === document.documentElement) {
    return true;
  }
  if (!document || !document.documentElement || !document.body) {
    return false;
  }

  const rect = element.getBoundingClientRect();
  if (isScrollModeEnabled()) {
    return rect.bottom > 0 && rect.top < window.innerHeight;
  } else {
    return rect.right > 0 && rect.left < window.innerWidth;
  }
}

function shouldIgnoreElement(element) {
  const elStyle = getComputedStyle(element);
  if (elStyle) {
    const display = elStyle.getPropertyValue("display");
    if (display != "block") {
      return true;
    }
    // Cannot be relied upon, because web browser engine reports invisible when out of view in
    // scrolled columns!
    // const visibility = elStyle.getPropertyValue("visibility");
    // if (visibility === "hidden") {
    //     return false;
    // }
    const opacity = elStyle.getPropertyValue("opacity");
    if (opacity === "0") {
      return true;
    }
  }

  return false;
}
