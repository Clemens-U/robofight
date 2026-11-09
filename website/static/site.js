(() => {
  if (!/^https?:$/.test(window.location.protocol)) return;

  const endpoint = "/site-event.htm";
  const product = document.body?.dataset.product === "opcode_fireline"
    ? "opcode_fireline"
    : "opcode_arena";
  let pageViewSent = false;

  const updateInternalTrafficMarker = () => {
    try {
      const url = new URL(window.location.href);
      const marker = url.searchParams.get("opcode_arena_internal");
      if (marker === "1") localStorage.setItem("opcode_arena_internal_traffic", "1");
      else if (marker === "0") localStorage.removeItem("opcode_arena_internal_traffic");
      else return;

      url.searchParams.delete("opcode_arena_internal");
      window.history.replaceState({}, document.title, `${url.pathname}${url.search}${url.hash}`);
    } catch {
      // Site statistics must never interrupt the page experience.
    }
  };

  const trafficType = () => {
    try {
      return localStorage.getItem("opcode_arena_internal_traffic") === "1" ? "internal" : "external";
    } catch {
      return "external";
    }
  };

  const referrerCategory = () => {
    if (!document.referrer) return "direct";
    try {
      const referrer = new URL(document.referrer);
      const currentHost = window.location.hostname.replace(/^www\./, "");
      const referrerHost = referrer.hostname.replace(/^www\./, "");
      if (referrerHost === currentHost) return "internal";
      if (/(^|\.)google\./i.test(referrerHost)) return "google";
      if (/(^|\.)bing\./i.test(referrerHost)) return "bing";
      if (/(^|\.)duckduckgo\.com$/i.test(referrerHost)) return "duckduckgo";
    } catch {
      return "other";
    }
    return "other";
  };

  const sendSiteEvent = (eventName, extra = {}) => {
    const body = JSON.stringify({
      event: eventName,
      path: window.location.pathname || "/",
      product,
      locale: "en",
      referrer: referrerCategory(),
      traffic_type: trafficType(),
      ...extra,
    });

    if (navigator.sendBeacon) {
      const queued = navigator.sendBeacon(endpoint, new Blob([body], { type: "application/json" }));
      if (queued) return;
    }

    if (window.fetch) {
      window.fetch(endpoint, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body,
        keepalive: true,
        credentials: "omit",
      }).catch(() => {});
    }
  };

  updateInternalTrafficMarker();

  const sendPageView = () => {
    if (pageViewSent) return;
    pageViewSent = true;
    sendSiteEvent("page_view");
  };

  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", sendPageView);
  else sendPageView();

  document.addEventListener("click", (event) => {
    const link = event.target.closest?.("a[href]");
    if (!link) return;

    const panel = link.dataset.panel;
    if (panel) {
      sendSiteEvent("panel_view", { path: `/panel/${panel}` });
      return;
    }

    try {
      const url = new URL(link.getAttribute("href") || "", window.location.href);
      const host = url.hostname.replace(/^www\./, "").toLowerCase();

      if (host === "play.google.com") {
        const appId = url.searchParams.get("id");
        if (appId === "robofight.android.pro") {
          sendSiteEvent("circuit_clicked");
          return;
        }
        if (appId === "robofight.android") {
          sendSiteEvent("free_app_clicked");
          return;
        }
      }

      if (host === "github.com" && /^\/Clemens-U\/robofight\/blob\/main\/MANUAL\.md\/?$/i.test(url.pathname)) {
        sendSiteEvent("manual_clicked");
        return;
      }

      if (host === "github.com" && /^\/Clemens-U\/robofight\/?$/i.test(url.pathname)) {
        sendSiteEvent("source_clicked");
        return;
      }

      if (url.hostname && url.hostname !== window.location.hostname) {
        sendSiteEvent("external_link_clicked");
      }
    } catch {
      // Ignore malformed or non-URL link targets.
    }
  });
})();
