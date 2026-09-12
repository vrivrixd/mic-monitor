/* Mic Monitor - listening page. */
(function () {
  'use strict';

  var MOBILE = /Android|webOS|iPhone|iPad|iPod|IEMobile|Opera Mini/i.test(navigator.userAgent);
  var DEFAULT_BUFFER_MS = 150;
  var MIN_BUFFER_MS = 30;
  var MAX_BUFFER_MS = 1000;
  /* Below this much slack the next block would arrive too late to play. */
  var MIN_LEAD_SEC = 0.02;
  /* A block under this peak counts as a pause and can go without anyone hearing. */
  var QUIET_PEAK = 0.02;

  /*
   * Language: first the full browser tag, such as pt-BR or zh-TW, then only the
   * base, such as pt or zh, and English as the last resort.
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
    channels: document.getElementById('channels'),
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
  /* True while we wait for a pause in the speech to shrink the slack. */
  var trimming = false;
  /* Rate the current context was created with, to avoid rebuilding it for nothing. */
  var builtForRate = 0;
  var started = false;
  var shuttingDown = false;
  var suppressUntil = 0;
  var statusTimer = null;

  /* --------------------------------------------------------------------- audio
   *
   * Every block that arrives becomes a piece scheduled on the audio timeline. What
   * plays it is the browser audio thread itself, so a stutter in the page no longer
   * cuts the sound: the slack chosen in the buffer absorbs the delay.
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
     * The context rate has to match the phone. If it were the rate of the sound card
     * in the computer, often 44100 against the 48000 of the phone, every twenty
     * millisecond block would be converted on its own, with the filter starting over
     * at each seam. That is fifty seams a second, inaudible in silence and heard as
     * light clicks over speech. With the rates equal, each block goes in with no
     * conversion at all and the final conversion happens once, on the way out.
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

  /** Loudest point of the block, sampled every fourth value to stay cheap. */
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
      /* The start, or the slack ran out. Begin again with the buffer time. */
      playTime = now + targetSec;
      trimming = false;
    } else if (lead > targetSec * 1.6 + 0.05) {
      /* The slack grew, because the clocks on the two sides never agree. */
      trimming = true;
    }

    /*
     * Dropping a block in the middle of speech clicks. So we wait for a pause: the
     * first nearly silent block is the one that goes. If the slack passes the
     * ceiling it goes anyway, because too much delay is worse.
     */
    if (trimming && (peakOf(view) < QUIET_PEAK || lead > targetSec * 3 + 0.4)) {
      /* Nothing scheduled and the clock stays put: the slack shrinks by one block. */
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

  /** Growing the slack costs a single silence, exactly as long as what is missing. */
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
      /* Rare after the click on Start, but the way back stays open. */
      el.controls.hidden = true;
      el.enable.hidden = false;
      setStatus(t('clickStart'), false);
    } else {
      el.enable.hidden = true;
      el.controls.hidden = false;
      describeStream();
    }
  }

  /* ---------------------------------------------------------------- sound card */

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

  /* With no listening going on nothing shows, so nobody changes what they cannot hear. */
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

  el.channels.addEventListener('change', function () {
    markLocalChange();
    /* In stereo the device picks the microphone on its own. */
    el.mic.disabled = el.channels.value !== 'mono';
    send({ type: 'setChannels', mode: el.channels.value });
  });

  el.buffer.addEventListener('change', function () {
    markLocalChange();
    var wanted = Math.round(Number(el.buffer.value));
    if (!isFinite(wanted)) wanted = DEFAULT_BUFFER_MS;
    /* Outside the useful range the number falls back to the nearest limit. */
    if (wanted < MIN_BUFFER_MS) wanted = MIN_BUFFER_MS;
    if (wanted > MAX_BUFFER_MS) wanted = MAX_BUFFER_MS;
    if (String(wanted) !== el.buffer.value) el.buffer.value = String(wanted);
    bufferMs = wanted;
    applyBuffer();
    send({ type: 'setBuffer', bufferMs: bufferMs });
  });

  /*
   * Leaving here disconnects this browser alone. The phone keeps streaming, and
   * another computer may take the slot. The Start button brings the sound back.
   */
  el.disconnect.addEventListener('click', function () {
    shuttingDown = true;
    if (ws) {
      /* Without the handlers, a deliberate close does not turn into a lost warning. */
      ws.onclose = null;
      ws.onerror = null;
      ws.onmessage = null;
      try { ws.close(); } catch (e) {}
    }
    ws = null;
    teardownAudio();
    hideEverything();
    setStatus(t('ended'), false);
    window.alert(t('alertEnded'));
    started = false;
    shuttingDown = false;
    config = null;
    if (!statusTimer) statusTimer = setInterval(checkStatus, 3000);
    checkStatus();
  });

  /* --------------------------------------------------------------- connection */

  function applyConfig(cfg) {
    var newStream = !config || config.streamId !== cfg.streamId;
    config = cfg;

    if (Date.now() >= suppressUntil) {
      el.gain.value = cfg.gainPercent;
      el.mic.value = cfg.source;
      el.channels.value = cfg.channelMode;
      el.buffer.value = String(cfg.bufferMs);
      if (bufferMs !== cfg.bufferMs) {
        bufferMs = cfg.bufferMs;
        applyBuffer();
      }
    }
    el.mic.disabled = el.channels.value !== 'mono';

    /* A new rate on the phone calls for a new context, to keep the rates equal. */
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
   * Nothing happens before the click. The page only takes the listener slot, and
   * only asks the phone for audio, after the person says to begin.
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
   * Before offering the button the page asks the phone whether the slot is free.
   * The question is an ordinary request, so it never displaces whoever is listening.
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
  /* The slot may open or close while the person looks at an idle page. */
  statusTimer = setInterval(checkStatus, 3000);
})();
