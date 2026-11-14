(() => {
  const reduce = matchMedia("(prefers-reduced-motion: reduce)").matches;
  const pt = document.documentElement.lang.startsWith("pt");

  // The PT/EN switch is remembered, so the automatic choice in <head> doesn't undo it.
  document.querySelectorAll("[data-lang]").forEach((a) =>
    a.addEventListener("click", () => {
      try {
        localStorage.setItem("lang", a.dataset.lang);
      } catch {}
    })
  );

  // Navigation gets a shadow once the page scrolls.
  const nav = document.getElementById("nav");
  const onScroll = () => nav.classList.toggle("scrolled", scrollY > 10);
  addEventListener("scroll", onScroll, { passive: true });
  onScroll();

  // Reveal on scroll, siblings slightly staggered.
  const reveal = new IntersectionObserver(
    (entries) => {
      for (const e of entries) {
        if (!e.isIntersecting) continue;
        e.target.classList.add("in");
        reveal.unobserve(e.target);
      }
    },
    { rootMargin: "0px 0px -8% 0px", threshold: 0.12 }
  );
  document.querySelectorAll("[data-reveal]").forEach((el) => {
    const siblings = [...el.parentElement.children].filter((c) => c.hasAttribute("data-reveal"));
    const i = siblings.indexOf(el);
    if (i > 0) el.style.setProperty("--d", Math.min(i, 6) * 0.08 + "s");
    reveal.observe(el);
  });

  // Videos load and play only while on screen.
  const videos = new IntersectionObserver(
    (entries) => {
      for (const e of entries) {
        const v = e.target;
        if (e.isIntersecting) {
          if (!v.getAttribute("src") && v.dataset.src) v.src = v.dataset.src;
          if (!reduce) v.play().catch(() => {});
        } else {
          v.pause();
        }
      }
    },
    { threshold: 0.25 }
  );
  document.querySelectorAll("video[data-src]").forEach((v) => videos.observe(v));

  // The hero TV leans toward the pointer.
  const heroTv = document.getElementById("heroTv");
  if (heroTv && !reduce && matchMedia("(pointer: fine)").matches) {
    const stage = heroTv.parentElement;
    addEventListener(
      "pointermove",
      (ev) => {
        const r = stage.getBoundingClientRect();
        const x = (ev.clientX - (r.left + r.width / 2)) / innerWidth;
        const y = (ev.clientY - (r.top + r.height / 2)) / innerHeight;
        heroTv.style.setProperty("--ry", (-12 + x * 12).toFixed(2) + "deg");
        heroTv.style.setProperty("--rx", (4 - y * 6).toFixed(2) + "deg");
      },
      { passive: true }
    );
  }

  // Before / after: drag, arrow keys, and one automatic sweep the first time it shows up.
  const ba = document.getElementById("ba");
  if (ba) {
    let x = 50;
    let dragging = false;
    let touched = false;
    const set = (v) => {
      x = Math.max(0, Math.min(100, v));
      ba.style.setProperty("--x", x + "%");
      ba.setAttribute("aria-valuenow", String(Math.round(x)));
    };
    const fromEvent = (ev) => {
      const r = ba.getBoundingClientRect();
      set(((ev.clientX - r.left) / r.width) * 100);
    };
    ba.addEventListener("pointerdown", (ev) => {
      dragging = true;
      touched = true;
      ba.setPointerCapture(ev.pointerId);
      fromEvent(ev);
    });
    ba.addEventListener("pointermove", (ev) => {
      if (dragging) fromEvent(ev);
    });
    const stop = () => (dragging = false);
    ba.addEventListener("pointerup", stop);
    ba.addEventListener("pointercancel", stop);
    ba.addEventListener("keydown", (ev) => {
      if (ev.key === "ArrowLeft" || ev.key === "ArrowRight") {
        touched = true;
        set(x + (ev.key === "ArrowLeft" ? -5 : 5));
        ev.preventDefault();
      }
    });
    set(50);
    const ease = (t) => (t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2);
    const sweep = () => {
      const keys = [
        [0, 50],
        [1000, 88],
        [2200, 12],
        [3200, 50],
      ];
      const t0 = performance.now();
      const step = (now) => {
        if (touched) return;
        const t = now - t0;
        let i = 0;
        while (i < keys.length - 2 && t > keys[i + 1][0]) i++;
        const [a, va] = keys[i];
        const [b, vb] = keys[i + 1];
        set(va + (vb - va) * ease(Math.min(1, Math.max(0, (t - a) / (b - a)))));
        if (t < keys[keys.length - 1][0]) requestAnimationFrame(step);
      };
      requestAnimationFrame(step);
    };
    if (!reduce) {
      const once = new IntersectionObserver(
        (entries) => {
          if (!entries[0].isIntersecting) return;
          once.disconnect();
          setTimeout(sweep, 500);
        },
        { threshold: 0.6 }
      );
      once.observe(ba);
    }
  }

  // Sunset: scrolling through the section turns day into night.
  const sunset = document.getElementById("sunset");
  if (sunset) {
    const sticky = sunset.querySelector(".sticky");
    const clock = document.getElementById("sunClock");
    const mix = (a, b, t) => a.map((v, i) => Math.round(v + (b[i] - v) * t));
    const rgb = (c) => `rgb(${c.join(",")})`;
    let last = -1;
    let queued = false;
    const update = () => {
      queued = false;
      const r = sunset.getBoundingClientRect();
      const total = Math.max(1, r.height - innerHeight);
      const p = Math.max(0, Math.min(1, -r.top / total));
      const e = p < 0.5 ? 2 * p * p : 1 - Math.pow(-2 * p + 2, 2) / 2;
      if (Math.abs(e - last) < 0.002) return;
      last = e;
      sunset.style.setProperty("--p", e.toFixed(3));
      sticky.style.setProperty("--sunset-bg", rgb(mix([245, 245, 247], [11, 11, 20], e)));
      sticky.style.setProperty("--sunset-ink", rgb(mix([29, 29, 31], [245, 245, 247], e)));
      sticky.style.setProperty("--sunset-muted", rgb(mix([110, 110, 115], [168, 168, 178], e)));
      sticky.style.setProperty("--sunset-faint", rgb(mix([142, 142, 147], [130, 132, 160], e)));
      const minutes = 17 * 60 + 20 + Math.round(e * 50); // 5:20 PM -> 6:10 PM
      const mm = String(minutes % 60).padStart(2, "0");
      clock.textContent = pt ? `${Math.floor(minutes / 60)}:${mm}` : `${Math.floor(minutes / 60) - 12}:${mm} PM`;
    };
    const queue = () => {
      if (queued) return;
      queued = true;
      requestAnimationFrame(update);
    };
    addEventListener("scroll", queue, { passive: true });
    addEventListener("resize", queue);
    update();
  }

  // Numbers count up when they come into view.
  const counters = new IntersectionObserver(
    (entries) => {
      for (const e of entries) {
        if (!e.isIntersecting) continue;
        counters.unobserve(e.target);
        const el = e.target;
        const to = Number(el.dataset.count);
        if (reduce) {
          el.textContent = String(to);
          continue;
        }
        const t0 = performance.now();
        const step = (now) => {
          const f = Math.min(1, (now - t0) / 1400);
          el.textContent = String(Math.round(to * (1 - Math.pow(1 - f, 3))));
          if (f < 1) requestAnimationFrame(step);
        };
        requestAnimationFrame(step);
      }
    },
    { threshold: 0.6 }
  );
  document.querySelectorAll("[data-count]").forEach((el) => counters.observe(el));

  // The frame-time line of the 60 FPS meter.
  const spark = document.getElementById("spark");
  if (spark) {
    const pts = Array.from({ length: 30 }, () => 12);
    const draw = () => spark.setAttribute("points", pts.map((y, i) => `${((i * 120) / 29).toFixed(1)},${y.toFixed(1)}`).join(" "));
    draw();
    if (!reduce) {
      setInterval(() => {
        pts.shift();
        pts.push(12 + (Math.random() - 0.5) * 3);
        draw();
      }, 120);
    }
  }

  // Install tabs.
  const tabs = [...document.querySelectorAll('[role="tab"]')];
  const select = (tab) => {
    for (const t of tabs) {
      const on = t === tab;
      t.setAttribute("aria-selected", String(on));
      t.tabIndex = on ? 0 : -1;
      document.getElementById(t.getAttribute("aria-controls")).hidden = !on;
    }
  };
  tabs.forEach((t, i) => {
    t.addEventListener("click", () => select(t));
    t.addEventListener("keydown", (ev) => {
      if (ev.key !== "ArrowLeft" && ev.key !== "ArrowRight") return;
      const next = tabs[(i + (ev.key === "ArrowRight" ? 1 : tabs.length - 1)) % tabs.length];
      select(next);
      next.focus();
    });
  });

  // Copy buttons.
  document.querySelectorAll(".copy-btn").forEach((b) =>
    b.addEventListener("click", async () => {
      const text = b.previousElementSibling.textContent.trim();
      try {
        await navigator.clipboard.writeText(text);
      } catch {
        const range = document.createRange();
        range.selectNodeContents(b.previousElementSibling);
        const sel = getSelection();
        sel.removeAllRanges();
        sel.addRange(range);
      }
      b.textContent = pt ? "Copiado" : "Copied";
      b.classList.add("done");
      setTimeout(() => {
        b.textContent = pt ? "Copiar" : "Copy";
        b.classList.remove("done");
      }, 1600);
    })
  );

  // Guided Claude onboarding prompt generator.
  const wizard = document.getElementById("onboardingWizard");
  if (wizard) {
    let selectedClub = "flamengo";
    let selectedClubName = "Flamengo";
    const clubBtns = [...wizard.querySelectorAll("[data-club]")];
    const cityInput = document.getElementById("wizCity");
    const weatherCheck = document.getElementById("wizWeather");
    const weatherStatus = document.getElementById("wizWeatherStatus");
    const debloatCheck = document.getElementById("wizDebloat");
    const adguardCheck = document.getElementById("wizAdguard");
    const ps5Check = document.getElementById("wizPs5");
    const tmdbCheck = document.getElementById("wizTmdb");
    const brandSelect = document.getElementById("wizBrand");
    const ipInput = document.getElementById("wizIp");
    const promptOut = document.getElementById("wizPrompt");
    const copyPromptBtn = document.getElementById("wizCopyPrompt");

    const buildPrompt = () => {
      const weatherOn = weatherCheck.checked;
      const cityVal = cityInput.value.trim();
      const ipVal = ipInput.value.trim();
      const brandVal = brandSelect.value;
      const debloatOn = debloatCheck.checked;
      const adguardOn = adguardCheck.checked;
      const ps5On = ps5Check.checked;
      const tmdbOn = tmdbCheck.checked;

      if (pt) {
        if (weatherOn) {
          weatherStatus.textContent = cityVal
            ? `Clima ativado para "${cityVal}" (o pôr do sol da cidade ajusta o tema claro/escuro).`
            : "Digite sua cidade acima ou desmarque para gerar a versão sem clima.";
        } else {
          weatherStatus.textContent = "Clima desativado — sua versão vai leve e sem previsão do tempo.";
        }

        const clubLine =
          selectedClub === "none"
            ? "- Aba de futebol: DESATIVADA (team=none)"
            : `- Aba de futebol: ${selectedClubName} (team=${selectedClub})`;
        const weatherLine = weatherOn
          ? `- Clima e pôr do sol: ATIVADO para "${cityVal || "perguntar minha cidade"}" (preencher latitude/longitude corretas em decimal no config.properties)`
          : "- Clima e pôr do sol: DESATIVADO (weather_enabled=false, city=)";
        const ipLine = ipVal
          ? `- IP da TV já informado: ${ipVal}`
          : "- IP da TV: ainda não sei — me guie passo a passo para ligar o Modo Desenvolvedor, ativar Depuração USB e pegar o IP na TV";

        promptOut.textContent = [
          "Quero instalar o Free My TV (fork com performance otimizada e aba de clube) na minha TV.",
          "Aja como um técnico paciente e amigo: conduza o onboarding UMA ETAPA CURTA POR VEZ em português simples, esperando minha resposta antes de mandar o próximo comando.",
          "",
          "Minhas escolhas do site:",
          `- Repositório: https://github.com/DIDIDXX/free-my-tv (branch tcl-performance)`,
          `- Marca / sistema da TV: ${brandVal}`,
          ipLine,
          clubLine,
          weatherLine,
          `- Otimizar apps lentos e desativar launcher original sem apagar nada: ${debloatOn ? "SIM" : "NÃO"}`,
          `- Ativar DNS Privado AdGuard (dns.adguard-dns.com) para cortar anúncios no sistema: ${adguardOn ? "SIM" : "NÃO"}`,
          `- Ativar ajuste automático de volume para PS5 no HDMI 1: ${ps5On ? "SIM" : "NÃO"}`,
          `- Recomendações de filmes/séries via TMDB grátis: ${tmdbOn ? "SIM (me ajude a gerar e colar minha chave grátis em config.properties sem expor aqui)" : "NÃO (deixar tmdb_key= vazio e instalar direto)"}`,
          "",
          "Como começar agora:",
          "1. Se o repositório ainda não estiver clonado aqui, faça `git clone -b tcl-performance https://github.com/DIDIDXX/free-my-tv.git && cd free-my-tv` e leia o `CLAUDE.md`.",
          "2. Verifique no computador o que já está instalado (JDK 17+, Android SDK e adb) sem me inundar com texto.",
          "3. Me diga apenas o primeiro passo bem mastigado para eu fazer no controle da TV (ativar Opções do Desenvolvedor / Depuração USB e pegar o IP) e aguarde meu retorno."
        ].join("\n");
      } else {
        if (weatherOn) {
          weatherStatus.textContent = cityVal
            ? `Weather enabled for "${cityVal}" (real sunset drives the day/night look).`
            : "Type your city above, or uncheck to build with weather disabled.";
        } else {
          weatherStatus.textContent = "Weather disabled — your build skips weather & Open-Meteo completely.";
        }

        const clubLine =
          selectedClub === "none"
            ? "- Football club tab: DISABLED (team=none)"
            : `- Football club tab: ${selectedClubName} (team=${selectedClub})`;
        const weatherLine = weatherOn
          ? `- Weather & sunset: ENABLED for "${cityVal || "ask my city"}" (fill decimal latitude/longitude in config.properties)`
          : "- Weather & sunset: DISABLED (weather_enabled=false, city=)";
        const ipLine = ipVal
          ? `- TV IP provided: ${ipVal}`
          : "- TV IP: unknown yet — walk me step by step through enabling Developer Options, USB debugging, and finding the TV IP";

        promptOut.textContent = [
          "I want to install Free My TV (TCL performance fork with optional club dashboard) on my TV.",
          "Act as a friendly, patient technician: guide me ONE SHORT STEP AT A TIME in plain language, waiting for my reply before moving to the next step.",
          "",
          "My choices from the website:",
          "- Repository: https://github.com/DIDIDXX/free-my-tv (branch tcl-performance)",
          `- TV brand / OS: ${brandVal}`,
          ipLine,
          clubLine,
          weatherLine,
          `- Optimize bloatware & disable stock launcher safely via ADB (reversible): ${debloatOn ? "YES" : "NO"}`,
          `- Enable AdGuard Private DNS (dns.adguard-dns.com): ${adguardOn ? "YES" : "NO"}`,
          `- Enable PS5 HDMI-1 auto volume mode: ${ps5On ? "YES" : "NO"}`,
          `- Include TMDB movie/show picks: ${tmdbOn ? "YES (guide me to create a free TMDB key and paste it into config.properties)" : "NO (leave tmdb_key= empty)"}`,
          "",
          "Start now:",
          "1. If the repo isn't cloned yet, run `git clone -b tcl-performance https://github.com/DIDIDXX/free-my-tv.git && cd free-my-tv` and read `CLAUDE.md`.",
          "2. Quietly check JDK 17+, Android SDK build-tools/platform-34, and adb.",
          "3. Give me just the first simple step to do with my TV remote and wait for my reply."
        ].join("\n");
      }
    };

    clubBtns.forEach((btn) => {
      btn.addEventListener("click", () => {
        clubBtns.forEach((b) => b.setAttribute("aria-pressed", String(b === btn)));
        selectedClub = btn.dataset.club;
        selectedClubName = btn.dataset.name || btn.textContent.trim();
        buildPrompt();
      });
    });

    weatherCheck.addEventListener("change", () => {
      cityInput.disabled = !weatherCheck.checked;
      buildPrompt();
    });

    [cityInput, debloatCheck, adguardCheck, ps5Check, tmdbCheck, brandSelect, ipInput].forEach((el) => {
      el.addEventListener("input", buildPrompt);
      el.addEventListener("change", buildPrompt);
    });

    const defaultLabel = copyPromptBtn.textContent;
    copyPromptBtn.addEventListener("click", async () => {
      const text = promptOut.textContent.trim();
      try {
        await navigator.clipboard.writeText(text);
      } catch {
        const range = document.createRange();
        range.selectNodeContents(promptOut);
        const sel = getSelection();
        sel.removeAllRanges();
        sel.addRange(range);
      }
      copyPromptBtn.textContent = pt ? "Prompt copiado! Cole no Claude ✓" : "Prompt copied! Paste into Claude ✓";
      copyPromptBtn.classList.add("done");
      setTimeout(() => {
        copyPromptBtn.textContent = defaultLabel;
        copyPromptBtn.classList.remove("done");
      }, 2200);
    });

    buildPrompt();
  }

  // Hero preview switcher (Club dashboard screenshot vs live Home video).
  const heroStage = document.getElementById("heroTv");
  const viewButtons = [...document.querySelectorAll("[data-hero-view]")];
  if (heroStage && viewButtons.length) {
    const panels = [...heroStage.querySelectorAll("[data-hero-panel]")];
    const setHeroView = (mode) => {
      for (const b of viewButtons) {
        const active = b.dataset.heroView === mode;
        b.setAttribute("aria-pressed", String(active));
      }
      for (const p of panels) {
        const show = p.dataset.heroPanel === mode;
        p.hidden = !show;
        const vid = p.querySelector("video");
        if (vid) {
          if (show) {
            if (!vid.getAttribute("src") && vid.dataset.src) vid.src = vid.dataset.src;
            if (!reduce) vid.play().catch(() => {});
          } else {
            vid.pause();
          }
        }
      }
    };
    viewButtons.forEach((b) => b.addEventListener("click", () => setHeroView(b.dataset.heroView)));
  }

  // The film.
  const film = document.getElementById("film");
  const filmVideo = film.querySelector("video");
  document.querySelectorAll("[data-film]").forEach((b) =>
    b.addEventListener("click", () => {
      film.showModal();
      filmVideo.play().catch(() => {});
    })
  );
  film.querySelector(".close").addEventListener("click", () => film.close());
  film.addEventListener("click", (e) => {
    if (e.target === film) film.close();
  });
  film.addEventListener("close", () => filmVideo.pause());
})();
