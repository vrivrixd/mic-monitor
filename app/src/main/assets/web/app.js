/* Mic Monitor - pagina de escuta. */
(function () {
  'use strict';

  var MOBILE = /Android|webOS|iPhone|iPad|iPod|IEMobile|Opera Mini/i.test(navigator.userAgent);
  var DEFAULT_BUFFER_MS = 150;
  /* Abaixo desta folga o proximo bloco chegaria tarde demais para tocar. */
  var MIN_LEAD_SEC = 0.02;
  /* Um bloco abaixo deste pico conta como pausa e pode sair sem ninguem ouvir. */
  var QUIET_PEAK = 0.02;

  /*
   * Idioma: primeiro a etiqueta completa do navegador, como pt-BR ou zh-TW, depois
   * so a base, como pt ou zh, e por fim o ingles.
   */
  var TEXTS = window.MIC_MONITOR_TEXTS || {};
  var BASE_TAG = { pt: 'pt-BR', zh: 'zh-CN', en: 'en' };
  var RTL = { ar: true };

  function pickLanguage() {
    var wanted = [];
    if (navigator.languages) wanted = wanted.concat(navigator.languages);
    if (navigator.language) wanted.push(navigator.language);
    for (var i = 0; i < wanted.length; i++) {
      var tag = String(wanted[i]);
      if (TEXTS[tag]) return tag;
      var base = tag.split('-')[0].toLowerCase();
      if (TEXTS[BASE_TAG[base]]) return BASE_TAG[base];
      if (TEXTS[base]) return base;
    }
    return 'en';
  }

  var LANG = pickLanguage();
  var T = TEXTS[LANG] || TEXTS.en || {};

  function t(key) {
    return T[key] || (TEXTS.en && TEXTS.en[key]) || key;
  }

  document.documentElement.lang = LANG;
  if (RTL[LANG.split('-')[0].toLowerCase()]) document.documentElement.dir = 'rtl';

  var marked = document.querySelectorAll('[data-i18n]');
  for (var m = 0; m < marked.length; m++) {
    marked[m].textContent = t(marked[m].getAttribute('data-i18n'));
  }

  var el = {
    main: document.getElementById('main'),
    unsupported: document.getElementById('unsupported'),
    status: document.getElementById('status'),
    enable: document.getElementById('enableAudio'),
    controls: document.getElementById('controls'),
    outputField: document.getElementById('outputField'),
    output: document.getElementById('output'),
    gain: document.getElementById('gain'),
    mute: document.getElementById('mute'),
    mic: document.getElementById('mic'),
    stereo: document.getElementById('stereo'),
    stereoHint: document.getElementById('stereoHint'),
    buffer: document.getElementById('buffer'),
    disconnect: document.getElementById('disconnect')
  };

  if (MOBILE) {
    el.unsupported.hidden = false;
    return;
  }
  el.main.hidden = false;

  var ws = null;
  var ctx = null;
  var muteNode = null;
  var config = null;
  var bufferMs = DEFAULT_BUFFER_MS;
  var targetSec = DEFAULT_BUFFER_MS / 1000;
  var playTime = 0;
  /* Verdadeiro enquanto esperamos uma pausa da fala para encolher a folga. */
  var trimming = false;
  /* Taxa com que o contexto atual foi criado, para nao refazer a toa. */
  var builtForRate = 0;
  var started = false;
  var shuttingDown = false;
  var suppressUntil = 0;
  var statusTimer = null;

  /* --------------------------------------------------------------------- audio
   *
   * Cada bloco que chega vira um trecho agendado na linha do tempo do som. Quem
   * toca e a propria thread de audio do navegador, entao um engasgo da pagina nao
   * corta mais o som: a folga escolhida no buffer absorve o atraso.
   */

  function teardownAudio() {
    if (muteNode) {
      try { muteNode.disconnect(); } catch (e) {}
    }
    if (ctx) {
      ctx.onstatechange = null;
      try { ctx.close(); } catch (e) {}
    }
    muteNode = null;
    ctx = null;
    playTime = 0;
    trimming = false;
    builtForRate = 0;
  }

  function buildAudio(cfg) {
    teardownAudio();

    var Ctor = window.AudioContext || window.webkitAudioContext;
    if (!Ctor) {
      setStatus(t('noWebAudio'), true);
      return;
    }

    /*
     * A taxa do contexto precisa ser a mesma do celular. Se ela for a do aparelho de
     * som do computador, muitas vezes 44100 contra os 48000 do celular, cada bloco de
     * vinte milissegundos seria convertido sozinho, com o filtro recomecando do zero
     * em cada emenda. Sao cinquenta emendas por segundo, inaudiveis no silencio e
     * ouvidas como estalos leves por cima da fala. Igualando a taxa, cada bloco entra
     * sem conversao nenhuma e a conversao final acontece uma vez so, na saida.
     */
    var wanted = (cfg && cfg.sampleRate) || 48000;
    try {
      ctx = new Ctor({ sampleRate: wanted, latencyHint: 'interactive' });
    } catch (e) {
      ctx = new Ctor({ latencyHint: 'interactive' });
    }
    ctx.onstatechange = refreshAudioGate;

    muteNode = ctx.createGain();
    muteNode.gain.value = el.mute.checked ? 0 : 1;
    muteNode.connect(ctx.destination);

    playTime = 0;
    trimming = false;
    builtForRate = wanted;
    setupOutputPicker();
    resumeAudio();
  }

  /** Maior amplitude do bloco, amostrada de quatro em quatro para sair barato. */
  function peakOf(view) {
    var peak = 0;
    for (var i = 0; i < view.length; i += 4) {
      var v = view[i];
      if (v < 0) v = -v;
      if (v > peak) peak = v;
    }
    return peak / 32768;
  }

  function pushPcm(raw) {
    if (!ctx || !config || ctx.state !== 'running') return;

    var view = new Int16Array(raw);
    var channels = config.channels === 2 ? 2 : 1;
    var rate = config.sampleRate || 48000;
    var frames = Math.floor(view.length / channels);
    if (frames <= 0) return;

    var duration = frames / rate;
    var now = ctx.currentTime;
    var lead = playTime - now;

    if (playTime === 0 || lead < MIN_LEAD_SEC) {
      /* Comeco, ou a folga acabou. Recomeca com o tempo escolhido no buffer. */
      playTime = now + targetSec;
      trimming = false;
    } else if (lead > targetSec * 1.6 + 0.05) {
      /* A folga cresceu, porque os relogios dos dois lados nunca batem. */
      trimming = true;
    }

    /*
     * Descartar um bloco no meio da fala estala. Entao esperamos uma pausa: o
     * primeiro bloco quase mudo e o que sai. Se a folga passar do teto, sai de
     * qualquer jeito, porque atraso demais e pior.
     */
    if (trimming && (peakOf(view) < QUIET_PEAK || lead > targetSec * 3 + 0.4)) {
      /* Nao agenda e nao avanca o relogio: a folga encolhe sozinha o tanto do bloco. */
      trimming = false;
      return;
    }

    var audio = ctx.createBuffer(2, frames, rate);
    var left = audio.getChannelData(0);
    var right = audio.getChannelData(1);
    var i;
    if (channels === 2) {
      for (i = 0; i < frames; i++) {
        left[i] = view[2 * i] / 32768;
        right[i] = view[2 * i + 1] / 32768;
      }
    } else {
      for (i = 0; i < frames; i++) {
        var v = view[i] / 32768;
        left[i] = v;
        right[i] = v;
      }
    }

    var source = ctx.createBufferSource();
    source.buffer = audio;
    source.connect(muteNode);
    source.start(playTime);
    playTime += duration;
  }

  /** Aumentar a folga custa um silencio unico, do tamanho exato do que falta. */
  function applyBuffer() {
    targetSec = bufferMs / 1000;
    if (!ctx || playTime === 0) return;
    var lead = playTime - ctx.currentTime;
    if (lead < targetSec) playTime += targetSec - lead;
  }

  function resumeAudio() {
    if (!ctx) return;
    try { ctx.resume(); } catch (e) {}
    refreshAudioGate();
  }

  function refreshAudioGate() {
    if (!ctx) return;
    if (ctx.state !== 'running') {
      /* Raro depois do clique em Iniciar, mas o caminho de volta fica aberto. */
      el.controls.hidden = true;
      el.enable.hidden = false;
      setStatus(t('clickStart'), false);
    } else {
      el.enable.hidden = true;
      el.controls.hidden = false;
      describeStream();
    }
  }

  /* ------------------------------------------------------------ placa de saida */

  function setupOutputPicker() {
    var available = !!(window.isSecureContext &&
      navigator.mediaDevices &&
      navigator.mediaDevices.enumerateDevices &&
      ctx && typeof ctx.setSinkId === 'function');
    if (!available) {
      el.outputField.hidden = true;
      return;
    }
    populateOutputs();
    if (navigator.mediaDevices.addEventListener) {
      navigator.mediaDevices.addEventListener('devicechange', populateOutputs);
    }
  }

  function populateOutputs() {
    navigator.mediaDevices.enumerateDevices().then(function (devices) {
      var outputs = devices.filter(function (d) { return d.kind === 'audiooutput'; });
      if (!outputs.length) {
        el.outputField.hidden = true;
        return;
      }
      var chosen = el.output.value;
      el.output.innerHTML = '';
      outputs.forEach(function (device, index) {
        var option = document.createElement('option');
        option.value = device.deviceId;
        option.textContent = device.label || (t('outputFallback') + ' ' + (index + 1));
        el.output.appendChild(option);
      });
      if (chosen) el.output.value = chosen;
      el.outputField.hidden = false;
    }, function () {
      el.outputField.hidden = true;
    });
  }

  el.output.addEventListener('change', function () {
    if (ctx && typeof ctx.setSinkId === 'function') {
      ctx.setSinkId(el.output.value).catch(function () {
        setStatus(t('sinkFailed'), true);
      });
    }
  });

  /* ------------------------------------------------------------------ interface */

  /* Sem escuta ativa nada aparece, para ninguem mexer no que nao esta ouvindo. */
  function hideEverything() {
    el.controls.hidden = true;
    el.enable.hidden = true;
  }

  function setStatus(text, isError) {
    el.status.textContent = text;
    el.status.classList.toggle('error', !!isError);
  }

  function describeStream() {
    if (!config) return;
    setStatus(config.paused ? t('paused') : t('listening'), false);
  }

  function send(message) {
    if (ws && ws.readyState === 1) ws.send(JSON.stringify(message));
  }

  function markLocalChange() {
    suppressUntil = Date.now() + 900;
  }

  el.gain.addEventListener('input', function () {
    markLocalChange();
    send({ type: 'setGain', gainPercent: Number(el.gain.value) });
  });

  el.mute.addEventListener('change', function () {
    if (muteNode) muteNode.gain.value = el.mute.checked ? 0 : 1;
  });

  el.mic.addEventListener('change', function () {
    markLocalChange();
    send({ type: 'setSource', source: el.mic.value });
  });

  el.stereo.addEventListener('change', function () {
    markLocalChange();
    el.mic.disabled = el.stereo.checked;
    send({ type: 'setStereo', stereo: el.stereo.checked });
  });

  el.buffer.addEventListener('change', function () {
    markLocalChange();
    bufferMs = Number(el.buffer.value);
    applyBuffer();
    send({ type: 'setBuffer', bufferMs: bufferMs });
  });

  el.disconnect.addEventListener('click', function () {
    shuttingDown = true;
    send({ type: 'shutdown' });
    setTimeout(function () {
      if (ws) { try { ws.close(); } catch (e) {} }
      teardownAudio();
      hideEverything();
      setStatus(t('ended'), false);
      window.alert(t('alertEnded'));
    }, 200);
  });

  /* ------------------------------------------------------------------ conexao */

  function applyConfig(cfg) {
    var newStream = !config || config.streamId !== cfg.streamId;
    config = cfg;

    if (Date.now() >= suppressUntil) {
      el.gain.value = cfg.gainPercent;
      el.mic.value = cfg.source;
      el.stereo.checked = cfg.stereo;
      el.buffer.value = String(cfg.bufferMs);
      if (bufferMs !== cfg.bufferMs) {
        bufferMs = cfg.bufferMs;
        applyBuffer();
      }
    }
    el.mic.disabled = el.stereo.checked;

    if (cfg.stereo && cfg.stereoKnown && !cfg.stereoReal) {
      el.stereoHint.textContent = t('stereoHint');
      el.stereoHint.hidden = false;
    } else {
      el.stereoHint.hidden = true;
    }

    /* Taxa nova do celular pede um contexto novo, para as taxas seguirem iguais. */
    if (!ctx || builtForRate !== cfg.sampleRate) {
      buildAudio(cfg);
    } else {
      if (newStream) {
        playTime = 0;
        trimming = false;
      }
      refreshAudioGate();
    }
  }

  function connect() {
    var scheme = location.protocol === 'https:' ? 'wss://' : 'ws://';
    ws = new WebSocket(scheme + location.host + '/ws');
    ws.binaryType = 'arraybuffer';

    ws.onmessage = function (event) {
      if (typeof event.data === 'string') {
        var message;
        try { message = JSON.parse(event.data); } catch (e) { return; }
        if (message.type === 'config') applyConfig(message);
        else if (message.type === 'busy') {
          shuttingDown = true;
          hideEverything();
          setStatus(t('busy'), true);
        }
        return;
      }
      pushPcm(event.data);
    };

    ws.onclose = function () {
      teardownAudio();
      hideEverything();
      if (!shuttingDown) {
        setStatus(t('lost'), true);
      }
    };

    ws.onerror = function () {
      if (!shuttingDown) setStatus(t('unreachable'), true);
    };
  }

  /*
   * Nada acontece antes do clique. A pagina so ocupa a vaga de ouvinte, e so pede
   * audio ao celular, depois que a pessoa manda comecar.
   */
  function start() {
    if (started) return;
    started = true;
    if (statusTimer) { clearInterval(statusTimer); statusTimer = null; }
    el.enable.hidden = true;
    setStatus(t('connecting'), false);
    connect();
  }

  el.enable.addEventListener('click', start);

  /*
   * Antes de oferecer o botao a pagina pergunta ao celular se a vaga esta livre.
   * A consulta e um pedido comum, entao nao tira o lugar de quem ja ouve.
   */
  function checkStatus() {
    if (started) return;
    fetch('/status', { cache: 'no-store' }).then(function (response) {
      return response.json();
    }).then(function (info) {
      if (started) return;
      if (info.busy) {
        el.enable.hidden = true;
        setStatus(t('busy'), true);
      } else {
        el.enable.hidden = false;
        setStatus(t('clickStart'), false);
      }
    }, function () {
      if (started) return;
      el.enable.hidden = false;
      setStatus(t('clickStart'), false);
    });
  }

  window.addEventListener('beforeunload', function () {
    if (ws) { try { ws.close(); } catch (e) {} }
  });

  el.controls.hidden = true;
  el.enable.hidden = true;
  setStatus(t('loading'), false);
  checkStatus();
  /* A vaga pode abrir ou fechar enquanto a pessoa olha a pagina parada. */
  statusTimer = setInterval(checkStatus, 3000);
})();
