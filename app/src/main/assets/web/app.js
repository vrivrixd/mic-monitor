/* Mic Monitor - pagina de escuta. */
(function () {
  'use strict';

  var MOBILE = /Android|webOS|iPhone|iPad|iPod|IEMobile|Opera Mini/i.test(navigator.userAgent);
  var DEFAULT_BUFFER_MS = 150;
  /* Abaixo desta folga o proximo bloco chegaria tarde demais para tocar. */
  var MIN_LEAD_SEC = 0.02;

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
  }

  function buildAudio() {
    teardownAudio();

    var Ctor = window.AudioContext || window.webkitAudioContext;
    if (!Ctor) {
      setStatus('Este navegador não tem suporte à Web Audio API.', true);
      return;
    }

    ctx = new Ctor({ latencyHint: 'interactive' });
    ctx.onstatechange = refreshAudioGate;

    muteNode = ctx.createGain();
    muteNode.gain.value = el.mute.checked ? 0 : 1;
    muteNode.connect(ctx.destination);

    playTime = 0;
    setupOutputPicker();
    resumeAudio();
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
    } else if (lead > targetSec * 1.6 + 0.05) {
      /* Folga grande demais, normalmente apos baixar o buffer. Descarta o bloco. */
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
      setStatus('Clique para começar a ouvir.', false);
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
    if (config.paused) {
      setStatus('Pausado. Outro aplicativo do celular está gravando.', false);
    } else {
      setStatus('Ouvindo...', false);
    }
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
      setStatus('Transmissão encerrada.', false);
      window.alert('A transmissão foi encerrada. O aplicativo do celular voltou para o estado parado.');
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
      el.stereoHint.textContent =
        'Este celular não entrega estéreo real. A transmissão continua em mono.';
      el.stereoHint.hidden = false;
    } else {
      el.stereoHint.hidden = true;
    }

    if (!ctx) {
      buildAudio();
    } else {
      if (newStream) playTime = 0;
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
          setStatus('Outro computador já está ouvindo.', true);
        }
        return;
      }
      pushPcm(event.data);
    };

    ws.onclose = function () {
      teardownAudio();
      hideEverything();
      if (!shuttingDown) {
        setStatus('Conexão perdida. Verifique se o aplicativo continua em execução e recarregue a página.', true);
      }
    };

    ws.onerror = function () {
      if (!shuttingDown) setStatus('Não foi possível falar com o celular.', true);
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
    setStatus('Conectando ao celular.', false);
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
        setStatus('Outro computador já está ouvindo.', true);
      } else {
        el.enable.hidden = false;
        setStatus('Clique para começar a ouvir.', false);
      }
    }, function () {
      if (started) return;
      el.enable.hidden = false;
      setStatus('Clique para começar a ouvir.', false);
    });
  }

  window.addEventListener('beforeunload', function () {
    if (ws) { try { ws.close(); } catch (e) {} }
  });

  el.controls.hidden = true;
  el.enable.hidden = true;
  setStatus('Carregando...', false);
  checkStatus();
  /* A vaga pode abrir ou fechar enquanto a pessoa olha a pagina parada. */
  statusTimer = setInterval(checkStatus, 3000);
})();
