/* Mic Monitor - pagina de escuta. */
(function () {
  'use strict';

  var MOBILE = /Android|webOS|iPhone|iPad|iPod|IEMobile|Opera Mini/i.test(navigator.userAgent);
  var SCRIPT_FRAMES = 2048;
  var DEFAULT_BUFFER_MS = 150;

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
  var node = null;
  var muteNode = null;
  var queue = null;
  var usingWorklet = false;
  var config = null;
  var bufferMs = DEFAULT_BUFFER_MS;
  var shuttingDown = false;
  var suppressUntil = 0;

  /* --------------------------------------------------------------- amortecedor */

  function PcmQueue() {
    this.chunks = [];
    this.queued = 0;
    this.offset = 0;
    this.priming = true;
    this.target = 0;
    this.max = 0;
  }

  PcmQueue.prototype.configure = function (target, max) {
    this.target = target;
    this.max = max;
    /* Buffer maior so vale se a fila voltar a encher ate a nova marca. */
    if (this.queued < target) this.priming = true;
  };

  PcmQueue.prototype.push = function (left, right) {
    this.chunks.push([left, right]);
    this.queued += left.length;
    if (this.priming && this.queued >= this.target) this.priming = false;
    /* Rede adiantada demais: joga fora o audio mais antigo em vez de acumular atraso. */
    while (this.queued > this.max && this.chunks.length > 1) {
      var first = this.chunks.shift();
      this.queued -= first[0].length - this.offset;
      this.offset = 0;
    }
  };

  PcmQueue.prototype.fill = function (L, R) {
    var n = L.length;
    if (this.priming) {
      L.fill(0);
      R.fill(0);
      return;
    }
    for (var i = 0; i < n; i++) {
      if (this.chunks.length === 0) {
        this.priming = true;
        L[i] = 0;
        R[i] = 0;
        continue;
      }
      var c = this.chunks[0];
      L[i] = c[0][this.offset];
      R[i] = c[1][this.offset];
      this.offset++;
      this.queued--;
      if (this.offset >= c[0].length) {
        this.chunks.shift();
        this.offset = 0;
      }
    }
  };

  /* ---------------------------------------------------------------------- audio */

  function bufferFrames() {
    var rate = ctx ? ctx.sampleRate : 48000;
    var target = Math.round(rate * bufferMs / 1000);
    var max = Math.round(target * 2.5) + Math.round(rate * 0.06);
    return { target: target, max: max };
  }

  function applyBuffer() {
    var frames = bufferFrames();
    if (usingWorklet && node) {
      node.port.postMessage({ type: 'configure', target: frames.target, max: frames.max });
    } else if (queue) {
      queue.configure(frames.target, frames.max);
    }
  }

  function teardownAudio() {
    if (node) {
      try { node.disconnect(); } catch (e) {}
      if (!usingWorklet) node.onaudioprocess = null;
    }
    if (muteNode) {
      try { muteNode.disconnect(); } catch (e) {}
    }
    if (ctx) {
      ctx.onstatechange = null;
      try { ctx.close(); } catch (e) {}
    }
    node = null;
    muteNode = null;
    ctx = null;
    queue = null;
    usingWorklet = false;
  }

  function buildAudio(cfg) {
    teardownAudio();

    var Ctor = window.AudioContext || window.webkitAudioContext;
    if (!Ctor) {
      setStatus('Este navegador não tem suporte à Web Audio API.', true);
      return;
    }

    try {
      ctx = new Ctor({ sampleRate: cfg.sampleRate, latencyHint: 'interactive' });
    } catch (e) {
      ctx = new Ctor();
    }
    ctx.onstatechange = refreshAudioGate;

    muteNode = ctx.createGain();
    muteNode.gain.value = el.mute.checked ? 0 : 1;
    muteNode.connect(ctx.destination);

    /* O caminho moderno so existe em conexao segura. Sem ele, o gerador classico
       cumpre o mesmo papel com um pouco mais de atraso. */
    if (ctx.audioWorklet) {
      ctx.audioWorklet.addModule('/worklet.js').then(function () {
        node = new AudioWorkletNode(ctx, 'mic-monitor-player', {
          numberOfInputs: 0,
          numberOfOutputs: 1,
          outputChannelCount: [2]
        });
        node.connect(muteNode);
        usingWorklet = true;
        afterAudioReady();
      }, function () {
        buildScriptProcessor();
        afterAudioReady();
      });
      return;
    }

    buildScriptProcessor();
    afterAudioReady();
  }

  function buildScriptProcessor() {
    queue = new PcmQueue();
    node = ctx.createScriptProcessor(SCRIPT_FRAMES, 1, 2);
    node.onaudioprocess = function (event) {
      var out = event.outputBuffer;
      queue.fill(out.getChannelData(0), out.getChannelData(1));
    };
    node.connect(muteNode);
    usingWorklet = false;
  }

  function afterAudioReady() {
    applyBuffer();
    setupOutputPicker();
    resumeAudio();
  }

  /*
   * Os navegadores seguram o audio ate a pessoa interagir com a pagina.
   * O botao resolve isso, e qualquer clique ou tecla tambem serve.
   */
  function resumeAudio() {
    if (!ctx) return;
    /* A promessa de resume fica pendente ate a pessoa interagir, entao o estado
       da pagina vem do proprio contexto e do evento de mudanca de estado. */
    try { ctx.resume(); } catch (e) {}
    refreshAudioGate();
  }

  function refreshAudioGate() {
    if (!ctx) return;
    var blocked = ctx.state !== 'running';
    el.enable.hidden = !blocked;
    if (blocked) {
      setStatus('Clique para começar a ouvir.', false);
    } else {
      describeStream();
    }
  }

  el.enable.addEventListener('click', resumeAudio);
  ['click', 'keydown', 'touchstart'].forEach(function (name) {
    document.addEventListener(name, function () {
      if (ctx && ctx.state !== 'running') resumeAudio();
    }, true);
  });

  function pushPcm(buffer) {
    if (!config) return;
    var view = new Int16Array(buffer);
    var channels = config.channels === 2 ? 2 : 1;
    var frames = Math.floor(view.length / channels);
    var left = new Float32Array(frames);
    var right = new Float32Array(frames);
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
    if (usingWorklet && node) {
      node.port.postMessage({ type: 'chunk', left: left, right: right }, [left.buffer, right.buffer]);
    } else if (queue) {
      queue.push(left, right);
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
        option.textContent = device.label || ('Saída de áudio ' + (index + 1));
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
        setStatus('Não foi possível usar essa placa de som.', true);
      });
    }
  });

  /* ------------------------------------------------------------------ interface */

  /* Sem conexao ativa os controles somem, para ninguem mexer no que nao esta ouvindo. */
  function hideControls() {
    el.controls.hidden = true;
    el.enable.hidden = true;
  }

  function setStatus(text, isError) {
    el.status.textContent = text;
    el.status.classList.toggle('error', !!isError);
  }

  function describeStream() {
    if (!config) return;
    setStatus('Ouvindo...', false);
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
      hideControls();
      setStatus('Transmissão encerrada.', false);
      window.alert('A transmissão foi encerrada. O aplicativo do celular voltou para o estado parado.');
    }, 200);
  });

  /* ------------------------------------------------------------------ conexao */

  function applyConfig(cfg) {
    var restart = !config || config.streamId !== cfg.streamId ||
      config.sampleRate !== cfg.sampleRate || config.channels !== cfg.channels;
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
      el.stereoHint.textContent =
        'Este celular não entrega estéreo real. A transmissão continua em mono.';
      el.stereoHint.hidden = false;
    } else {
      el.stereoHint.hidden = true;
    }

    if (restart) {
      buildAudio(cfg);
    } else {
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
          hideControls();
          setStatus('Outro computador já está ouvindo.', true);
        }
        return;
      }
      pushPcm(event.data);
    };

    ws.onclose = function () {
      teardownAudio();
      hideControls();
      if (!shuttingDown) {
        setStatus('Conexão perdida. Verifique se o aplicativo continua em execução e recarregue a página.', true);
      }
    };

    ws.onerror = function () {
      if (!shuttingDown) setStatus('Não foi possível falar com o celular.', true);
    };
  }

  window.addEventListener('beforeunload', function () {
    if (ws) { try { ws.close(); } catch (e) {} }
  });

  connect();
})();
