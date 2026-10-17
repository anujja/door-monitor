/**
 * MyGate history card, bundled with the MyGate integration, which loads it on every dashboard.
 *
 * Lists MyGate's latest alerts, newest first: the visitor's photo, name and where they're from,
 * when they came and how they were answered. Tap one for its full text and a large photo. Reads
 * the `history` attribute of the integration's "Last alert" sensor.
 *
 *   type: custom:mygate-history-card
 *   entity: sensor.mygate_last_alert   # optional: found automatically
 *   title: Visitors                    # optional; empty for no header
 *   limit: 10                          # alerts shown before "Show more"
 *   types: [approval, general]         # optional: only these (approval, general, vehicle, emergency)
 *   filter: "(checked|approv)"         # optional: only alerts whose text matches; see DEFAULT_FILTER
 */

const CARD = "mygate-history-card";

/**
 * Alerts shown when the card sets no `filter`: those whose text has one of these words, ignoring
 * case. That keeps check-ins, approvals and their outcomes, invites and kids' alerts, and hides
 * MyGate's marketing. The tablet's own filter defaults to the same. `.*` shows everything.
 */
const DEFAULT_FILTER = ".*(checked|approv|declined|denied|invite|kid).*";

const TYPES = {
  approval: "Visitors at the gate",
  general: "Entries and notices",
  vehicle: "Vehicles",
  emergency: "Emergencies",
};

const OUTCOMES = {
  waiting: ["Waiting", "var(--warning-color, #ffa600)"],
  approved: ["Approved", "var(--success-color, #43a047)"],
  denied: ["Denied", "var(--error-color, #db4437)"],
  left_at_gate: ["Left at gate", "var(--info-color, #039be5)"],
  timeout: ["No answer", "var(--secondary-text-color, #727272)"],
  answered: ["Answered", "var(--secondary-text-color, #727272)"],
};

const STYLE = `
  :host { display: block; }
  ha-card { overflow: hidden; padding-bottom: 8px; }
  .header {
    padding: 16px 16px 0;
    font-size: var(--ha-card-header-font-size, 24px);
    color: var(--ha-card-header-color, var(--primary-text-color));
  }
  .day {
    padding: 14px 16px 4px;
    font-size: 12px;
    font-weight: 500;
    letter-spacing: 0.06em;
    text-transform: uppercase;
    color: var(--secondary-text-color);
  }
  .row { display: flex; gap: 14px; align-items: center; padding: 8px 16px; cursor: pointer; }
  .pic {
    flex: none;
    width: 56px;
    height: 56px;
    border-radius: 12px;
    overflow: hidden;
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--primary-color);
    background: color-mix(in srgb, var(--primary-color) 14%, transparent);
    --mdc-icon-size: 30px;
  }
  .pic img { width: 100%; height: 100%; object-fit: cover; }
  .main { flex: 1; min-width: 0; }
  /* The company wraps under the name rather than either being cut short. */
  .who { display: flex; flex-wrap: wrap; align-items: center; gap: 4px 8px; min-width: 0; }
  .name {
    font-size: 16px;
    font-weight: 600;
    color: var(--primary-text-color);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .company {
    flex: none;
    display: inline-flex;
    align-items: center;
    gap: 6px;
    max-width: 100%;
    padding: 2px 10px;
    border-radius: 999px;
    font-size: 13px;
    font-weight: 600;
    color: var(--primary-text-color);
    background: color-mix(in srgb, var(--primary-color) 18%, transparent);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .company img { width: 18px; height: 18px; border-radius: 4px; background: #fff; object-fit: contain; }
  .what, .by {
    margin-top: 2px;
    font-size: 13px;
    color: var(--secondary-text-color);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .open .what, .open .by { white-space: normal; }
  .side { flex: none; display: flex; flex-direction: column; align-items: flex-end; gap: 4px; }
  .time { font-size: 13px; color: var(--secondary-text-color); white-space: nowrap; }
  .chip {
    padding: 2px 8px;
    border-radius: 999px;
    font-size: 12px;
    font-weight: 600;
    white-space: nowrap;
    color: var(--chip);
    background: color-mix(in srgb, var(--chip) 16%, transparent);
  }
  .photo {
    display: block;
    max-width: calc(100% - 102px);
    max-height: 360px;
    margin: 0 16px 8px 86px;
    border-radius: 12px;
  }
  .more {
    display: block;
    width: 100%;
    margin-top: 8px;
    padding: 12px;
    border: none;
    border-top: 1px solid var(--divider-color);
    background: none;
    color: var(--primary-color);
    font: inherit;
    font-weight: 500;
    cursor: pointer;
  }
  .empty { padding: 16px; color: var(--secondary-text-color); }
`;

const esc = (value) =>
  String(value ?? "").replace(
    /[&<>"']/g,
    (ch) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[ch],
  );

/** Only web URLs; MyGate sends some photos as http://, which an https dashboard won't load. */
const imageUrl = (url) =>
  typeof url === "string" && /^https?:\/\//i.test(url) ? url.replace(/^http:/i, "https:") : null;

/** The integration's "Last alert" sensor. */
function findEntity(hass) {
  const ids = Object.keys(hass.states).filter(
    (id) => id.startsWith("sensor.") && Array.isArray(hass.states[id].attributes.history),
  );
  return ids.find((id) => hass.entities?.[id]?.platform === "mygate") ?? ids.find((id) => id.includes("mygate"));
}

function iconFor(entry) {
  if (entry.type === "vehicle") return "mdi:car";
  if (entry.type === "emergency") return "mdi:alarm-light";
  if (entry.name || entry.type === "approval") return "mdi:account";
  return "mdi:bell-outline";
}

/** Declares the card and registers it, with the card picker too. */
function defineCard() {
  // Declared here, not at load: `HTMLElement` must be the one Home Assistant's registry patches.
  class MyGateHistoryCard extends HTMLElement {
    static getStubConfig(hass) {
      return { entity: findEntity(hass) ?? "" };
    }

    static getConfigForm() {
      return {
        schema: [
          { name: "entity", selector: { entity: { domain: "sensor", integration: "mygate" } } },
          { name: "title", selector: { text: {} } },
          { name: "limit", selector: { number: { min: 1, max: 100, mode: "box" } } },
          { name: "filter", selector: { text: {} } },
          {
            name: "types",
            selector: {
              select: {
                multiple: true,
                options: Object.entries(TYPES).map(([value, label]) => ({ value, label })),
              },
            },
          },
        ],
        computeLabel: (field) =>
          ({
            entity: "MyGate's Last alert sensor",
            title: "Title",
            limit: "Alerts shown before \"Show more\"",
            types: "Only these alerts (all when empty)",
            filter: "Only alerts whose text matches (regular expression)",
          })[field.name],
        computeHelper: (field) =>
          field.name === "filter"
            ? `Anywhere in the text, ignoring case. Visitors waiting at the gate and emergencies always show. Empty uses ${DEFAULT_FILTER}, which hides MyGate's marketing; .* shows everything.`
            : undefined,
      };
    }

    setConfig(config) {
      if (config.types != null && !Array.isArray(config.types)) {
        throw new Error("types must be a list, e.g. [approval, general]");
      }
      try {
        this._filter = new RegExp(config.filter || DEFAULT_FILTER, "i");
      } catch (err) {
        throw new Error(`filter isn't a valid regular expression: ${err.message}`);
      }
      this._config = { title: "MyGate", limit: 10, ...config };
      this._shown = Number(this._config.limit) || 10;
      this._open = new Set();
      if (!this.shadowRoot) {
        this.attachShadow({ mode: "open" });
        this.shadowRoot.addEventListener("click", (ev) => this._onClick(ev));
      }
      this._render();
    }

    set hass(hass) {
      const first = !this._hass;
      this._hass = hass;
      const entity = this._config.entity || findEntity(hass);
      const stateObj = entity ? hass.states[entity] : undefined;
      if (!first && stateObj === this._stateObj) return;
      this._stateObj = stateObj;
      this._render();
    }

    getCardSize() {
      return 1 + Math.ceil(Math.min(this._shown ?? 10, this._entries().length) * 1.5);
    }

    _entries() {
      const history = this._stateObj?.attributes?.history;
      const types = this._config?.types?.length ? this._config.types : null;
      return (Array.isArray(history) ? history : []).filter(
        (e) => (!types || types.includes(e.type)) && this._matches(e),
      );
    }

    /** The filter, except that visitors who could be answered and emergencies always show. */
    _matches(entry) {
      return entry.outcome != null || entry.type === "emergency" || this._filter.test(entry.text ?? "");
    }

    _onClick(ev) {
      const more = ev.target.closest(".more");
      if (more) {
        this._shown += Number(this._config.limit) || 10;
        this._render();
        return;
      }
      const row = ev.target.closest(".row");
      if (row) {
        const id = row.dataset.id;
        this._open.has(id) ? this._open.delete(id) : this._open.add(id);
        this._render();
      }
    }

    _formats() {
      const hass = this._hass;
      const lang = hass.locale?.language || hass.language || navigator.language;
      const timeZone = hass.locale?.time_zone === "local" ? undefined : hass.config?.time_zone;
      const relative = new Intl.RelativeTimeFormat(lang, { numeric: "auto" });
      const capital = (s) => s.charAt(0).toUpperCase() + s.slice(1);
      const key = new Intl.DateTimeFormat("en-CA", { timeZone, year: "numeric", month: "2-digit", day: "2-digit" });
      const today = key.format(new Date());
      const yesterday = key.format(new Date(Date.now() - 86400000));
      const longDay = new Intl.DateTimeFormat(lang, { timeZone, weekday: "long", day: "numeric", month: "long" });
      return {
        time: new Intl.DateTimeFormat(lang, { timeZone, hour: "numeric", minute: "2-digit" }),
        dayKey: (date) => key.format(date),
        dayLabel: (date) => {
          const k = key.format(date);
          if (k === today) return capital(relative.format(0, "day"));
          if (k === yesterday) return capital(relative.format(-1, "day"));
          return longDay.format(date);
        },
      };
    }

    _row(entry, fmt) {
      const open = this._open.has(entry.id);
      const photo = imageUrl(entry.image);
      const logo = imageUrl(entry.company_logo);
      const icon = iconFor(entry);
      const outcome = OUTCOMES[entry.outcome];
      // The name leads; without one, MyGate's title does.
      const headline = entry.name || entry.title || "MyGate";
      const detail = entry.text || (entry.name ? entry.title : "");
      return `
        <div class="row${open ? " open" : ""}" data-id="${esc(entry.id)}">
          <div class="pic">${
            photo
              ? `<img src="${esc(photo)}" alt="" loading="lazy" data-icon="${icon}">`
              : `<ha-icon icon="${icon}"></ha-icon>`
          }</div>
          <div class="main">
            <div class="who">
              <span class="name">${esc(headline)}</span>
              ${
                entry.company
                  ? `<span class="company">${logo ? `<img src="${esc(logo)}" alt="" class="logo">` : ""}${esc(entry.company)}</span>`
                  : ""
              }
            </div>
            ${detail ? `<div class="what">${esc(detail)}</div>` : ""}
            ${entry.outcome_text ? `<div class="by">${esc(entry.outcome_text)}</div>` : ""}
          </div>
          <div class="side">
            <span class="time">${esc(fmt.time.format(new Date(entry.time)))}</span>
            ${outcome ? `<span class="chip" style="--chip: ${outcome[1]}">${outcome[0]}</span>` : ""}
            ${entry.test ? `<span class="chip" style="--chip: var(--secondary-text-color)">Test</span>` : ""}
          </div>
        </div>
        ${open && photo ? `<img class="photo" src="${esc(photo)}" alt="Visitor photo">` : ""}`;
    }

    _render() {
      const root = this.shadowRoot;
      if (!root || !this._config) return;
      const title = this._config.title;
      let body = "";
      if (this._hass && !this._stateObj) {
        body = `<div class="empty">${
          this._config.entity
            ? `${esc(this._config.entity)} wasn't found.`
            : "MyGate's Last alert sensor wasn't found. Is the MyGate integration set up?"
        }</div>`;
      } else if (this._hass) {
        const entries = this._entries();
        if (!entries.length) {
          body = `<div class="empty">No MyGate alerts yet.</div>`;
        } else {
          const fmt = this._formats();
          let day = null;
          for (const entry of entries.slice(0, this._shown)) {
            const date = new Date(entry.time);
            if (fmt.dayKey(date) !== day) {
              day = fmt.dayKey(date);
              body += `<div class="day">${esc(fmt.dayLabel(date))}</div>`;
            }
            body += this._row(entry, fmt);
          }
          const left = entries.length - this._shown;
          if (left > 0) {
            body += `<button class="more">Show ${Math.min(left, Number(this._config.limit) || 10)} more</button>`;
          }
        }
      }
      root.innerHTML = `<style>${STYLE}</style><ha-card>${
        title ? `<div class="header">${esc(title)}</div>` : ""
      }${body}</ha-card>`;

      // A photo that won't load gives way to the alert's icon, a logo to the company's name alone.
      root.querySelectorAll(".pic img").forEach((img) =>
        img.addEventListener("error", () => {
          const icon = document.createElement("ha-icon");
          icon.setAttribute("icon", img.dataset.icon);
          img.replaceWith(icon);
        }),
      );
      root.querySelectorAll(".company img, img.photo").forEach((img) =>
        img.addEventListener("error", () => img.remove()),
      );
    }
  }

  customElements.define(CARD, MyGateHistoryCard);
  window.customCards = window.customCards || [];
  window.customCards.push({
    type: CARD,
    name: "MyGate history",
    description: "MyGate's latest visitors and alerts: photo, name, where they're from, and how they were answered.",
    preview: true,
    documentationURL:
      "https://github.com/anujja/door-monitor/tree/main/homeassistant/custom_components/mygate",
  });
}

// Home Assistant swaps in its own custom element registry (a scoped-registry polyfill) as it
// starts, and dashboards only find cards defined through that one. This file can load before
// the swap, so define the card once Home Assistant's app element exists.
customElements.whenDefined("home-assistant").then(() => {
  if (!customElements.get(CARD)) defineCard();
});
