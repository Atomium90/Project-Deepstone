// Shared XML reading helpers for Tiled's native .tmx/.tsx save format, used by both
// convert-tiled-room.mjs and generate-tiled-tileset-atlas.mjs. Just enough of a DOM reader to
// pull properties/attributes out of these files - not a general-purpose XML library.

import { JSDOM } from "jsdom";

const domParser = new (new JSDOM().window.DOMParser)();

/** Parses an XML string into a Document, failing loudly (not throwing) on malformed XML - both
 * callers want the same "print a clear message and exit" behavior rather than a raw stack trace. */
export function parseXmlDoc(text, path) {
    const doc = domParser.parseFromString(text, "text/xml");
    if (doc.querySelector("parsererror")) {
        console.error(`"${path}" is not valid XML - open and re-save it in Tiled.`);
        process.exit(1);
    }
    return doc;
}

/** Reads a <properties> element's children into Tiled's own `[{name, value}]` shape (matching a
 * JSON export's `properties` array 1:1), coercing bool/int/float types along the way. */
export function readPropertiesEl(propertiesEl) {
    if (!propertiesEl) return [];
    return [...propertiesEl.querySelectorAll(":scope > property")].map((p) => {
        const name = p.getAttribute("name");
        const type = p.getAttribute("type") || "string";
        // Tiled usually writes a property's value as the `value` attribute, but falls back to the
        // element's inner text for a multi-line string property - either way it ends up as text
        // here, coerced below by `type`.
        let value = p.hasAttribute("value") ? p.getAttribute("value") : p.textContent;
        if (type === "bool") value = value === "true";
        else if (type === "int" || type === "float") value = Number(value);
        return { name, value };
    });
}
