/* Gerador de audio para conexoes seguras. Mantem o amortecedor fora da thread principal. */
class MicMonitorPlayer extends AudioWorkletProcessor {
  constructor() {
    super();
    this.chunks = [];
    this.queued = 0;
    this.offset = 0;
    this.priming = true;
    this.target = 0;
    this.max = 0;

    this.port.onmessage = (event) => {
      const data = event.data;
      if (data.type === 'chunk') {
        this.chunks.push([data.left, data.right]);
        this.queued += data.left.length;
        if (this.priming && this.queued >= this.target) this.priming = false;
        while (this.queued > this.max && this.chunks.length > 1) {
          const first = this.chunks.shift();
          this.queued -= first[0].length - this.offset;
          this.offset = 0;
        }
      } else if (data.type === 'configure') {
        this.target = data.target;
        this.max = data.max;
        /* Buffer maior so vale se a fila voltar a encher ate a nova marca. */
        if (this.queued < this.target) this.priming = true;
      } else if (data.type === 'reset') {
        this.chunks = [];
        this.queued = 0;
        this.offset = 0;
        this.priming = true;
      }
    };
  }

  process(inputs, outputs) {
    const out = outputs[0];
    const left = out[0];
    const right = out.length > 1 ? out[1] : out[0];
    const n = left.length;

    if (this.priming) {
      left.fill(0);
      if (right !== left) right.fill(0);
      return true;
    }

    for (let i = 0; i < n; i++) {
      if (this.chunks.length === 0) {
        this.priming = true;
        left[i] = 0;
        if (right !== left) right[i] = 0;
        continue;
      }
      const chunk = this.chunks[0];
      left[i] = chunk[0][this.offset];
      if (right !== left) right[i] = chunk[1][this.offset];
      this.offset++;
      this.queued--;
      if (this.offset >= chunk[0].length) {
        this.chunks.shift();
        this.offset = 0;
      }
    }
    return true;
  }
}

registerProcessor('mic-monitor-player', MicMonitorPlayer);
