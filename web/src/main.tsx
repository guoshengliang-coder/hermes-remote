import { render } from "preact";
import { App } from "./App";
import { blockPageZoom } from "./app/noZoom";
import "./styles.css";

const root = document.getElementById("app");
if (root) render(<App />, root);
blockPageZoom();

// The service worker only caches the shell and hashed assets (never /v2/* or any API); it is
// registered in production builds only so development always sees fresh modules.
if (import.meta.env.PROD && "serviceWorker" in navigator) {
  window.addEventListener("load", () => {
    navigator.serviceWorker.register(`${import.meta.env.BASE_URL}sw.js`, { scope: import.meta.env.BASE_URL }).catch(() => {
      /* offline shell is optional */
    });
  });
}
