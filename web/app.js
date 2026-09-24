/**
 * SkyFPV DVR - Web Architecture & Simulator Engine
 * Low-latency UVC streaming, synthetic flight simulation, and DVR recording.
 */

class SkyFpvApp {
  constructor() {
    this.mediaStream = null;
    this.mediaRecorder = null;
    this.recordedChunks = [];
    this.recordStartTime = 0;
    this.recordTimerInterval = null;

    this.isSimActive = false;
    this.simAnimationId = null;
    this.isVrMode = false;
    this.aspectRatios = ['4-3', '16-9', 'fill'];
    this.aspectIndex = 0;

    this.savedFlights = [];

    // DOM Elements
    this.liveVideo = document.getElementById('liveVideo');
    this.simCanvas = document.getElementById('simCanvas');
    this.simCtx = this.simCanvas.getContext('2d');

    this.vrCanvasLeft = document.getElementById('vrCanvasLeft');
    this.vrCanvasRight = document.getElementById('vrCanvasRight');
    this.vrCtxLeft = this.vrCanvasLeft.getContext('2d');
    this.vrCtxRight = this.vrCanvasRight.getContext('2d');

    this.viewportWrapper = document.getElementById('viewportWrapper');
    this.singleViewport = document.getElementById('singleViewport');
    this.vrViewport = document.getElementById('vrViewport');
    this.noSignalOverlay = document.getElementById('noSignalOverlay');
    this.scanlinesOverlay = document.getElementById('scanlinesOverlay');

    // OSD Telemetry
    this.osdStatusPill = document.getElementById('osdStatusPill');
    this.osdStatusText = document.getElementById('osdStatusText');
    this.statusDot = document.getElementById('statusDot');
    this.osdRecPill = document.getElementById('osdRecPill');
    this.osdRecTimer = document.getElementById('osdRecTimer');
    this.osdFps = document.getElementById('osdFps');
    this.osdAspect = document.getElementById('osdAspect');
    this.pitchLadder = document.getElementById('pitchLadder');

    // Controls
    this.btnConnectUsb = document.getElementById('btnConnectUsb');
    this.btnStartSim = document.getElementById('btnStartSim');
    this.btnRecord = document.getElementById('btnRecord');
    this.btnSnapshot = document.getElementById('btnSnapshot');
    this.btnToggleVr = document.getElementById('btnToggleVr');
    this.btnToggleAspect = document.getElementById('btnToggleAspect');
    this.btnToggleScanlines = document.getElementById('btnToggleScanlines');
    this.btnGallery = document.getElementById('btnGallery');
    this.btnSettings = document.getElementById('btnSettings');

    // Modals
    this.galleryModal = document.getElementById('galleryModal');
    this.galleryGrid = document.getElementById('galleryGrid');
    this.galleryEmpty = document.getElementById('galleryEmpty');
    this.galleryCount = document.getElementById('galleryCount');
    this.btnCloseGallery = document.getElementById('btnCloseGallery');

    this.settingsModal = document.getElementById('settingsModal');
    this.btnCloseSettings = document.getElementById('btnCloseSettings');
    this.videoDeviceSelect = document.getElementById('videoDeviceSelect');
    this.chkAudioRecord = document.getElementById('chkAudioRecord');
    this.chkStaticNoise = document.getElementById('chkStaticNoise');

    this.init();
  }

  init() {
    this.bindEvents();
    this.loadVideoDevices();
    this.resizeCanvases();
    window.addEventListener('resize', () => this.resizeCanvases());

    // FPS Counter variables
    this.fpsCounter = 0;
    this.lastFpsCheck = performance.now();
    setInterval(() => this.updateFps(), 1000);
  }

  resizeCanvases() {
    const width = 640;
    const height = 480;
    this.simCanvas.width = width;
    this.simCanvas.height = height;
    this.vrCanvasLeft.width = width;
    this.vrCanvasRight.width = height;
  }

  bindEvents() {
    this.btnConnectUsb.addEventListener('click', () => this.startLiveCapture());
    this.btnStartSim.addEventListener('click', () => this.startSimulator());

    this.btnRecord.addEventListener('click', () => this.toggleRecording());
    this.btnSnapshot.addEventListener('click', () => this.takeSnapshot());

    this.btnToggleVr.addEventListener('click', () => this.toggleVrMode());
    this.btnToggleAspect.addEventListener('click', () => this.cycleAspectRatio());
    this.btnToggleScanlines.addEventListener('click', () => {
      this.scanlinesOverlay.classList.toggle('hidden');
      this.btnToggleScanlines.classList.toggle('active');
    });

    this.btnGallery.addEventListener('click', () => this.openGallery());
    this.btnCloseGallery.addEventListener('click', () => this.galleryModal.classList.remove('open'));

    this.btnSettings.addEventListener('click', () => this.settingsModal.classList.add('open'));
    this.btnCloseSettings.addEventListener('click', () => this.settingsModal.classList.remove('open'));

    this.videoDeviceSelect.addEventListener('change', () => {
      if (this.videoDeviceSelect.value) {
        this.startLiveCapture(this.videoDeviceSelect.value);
      }
    });

    // Close modals on backdrop click
    this.galleryModal.addEventListener('click', (e) => {
      if (e.target === this.galleryModal) this.galleryModal.classList.remove('open');
    });
    this.settingsModal.addEventListener('click', (e) => {
      if (e.target === this.settingsModal) this.settingsModal.classList.remove('open');
    });
  }

  async loadVideoDevices() {
    try {
      const devices = await navigator.mediaDevices.enumerateDevices();
      const videoInputs = devices.filter(d => d.kind === 'videoinput');

      this.videoDeviceSelect.innerHTML = '<option value="">Default UVC Camera / Receiver</option>';
      videoInputs.forEach((device, index) => {
        const option = document.createElement('option');
        option.value = device.deviceId;
        option.textContent = device.label || `Video Device ${index + 1}`;
        this.videoDeviceSelect.appendChild(option);
      });
    } catch (e) {
      console.warn('Could not enumerate video devices:', e);
    }
  }

  async startLiveCapture(deviceId = null) {
    this.stopSimulator();

    const constraints = {
      video: {
        width: { ideal: 640 },
        height: { ideal: 480 },
        frameRate: { ideal: 60 }
      },
      audio: this.chkAudioRecord.checked
    };

    if (deviceId) {
      constraints.video.deviceId = { exact: deviceId };
    }

    try {
      if (this.mediaStream) {
        this.mediaStream.getTracks().forEach(t => t.stop());
      }

      this.mediaStream = await navigator.mediaDevices.getUserMedia(constraints);
      this.liveVideo.srcObject = this.mediaStream;
      await this.liveVideo.play();

      this.liveVideo.style.display = 'block';
      this.simCanvas.style.display = 'none';
      this.noSignalOverlay.classList.add('hidden');

      this.statusDot.className = 'status-indicator streaming';
      this.osdStatusText.textContent = 'UVC 5.8G ACTIVE';

      // Start VR loop if VR mode is on
      this.startVrRenderLoop();
    } catch (err) {
      console.warn('Live capture failed or permission denied, falling back to simulator:', err);
      alert('Could not open hardware video feed (' + err.message + '). Launching FPV Simulator mode.');
      this.startSimulator();
    }
  }

  startSimulator() {
    if (this.mediaStream) {
      this.mediaStream.getTracks().forEach(t => t.stop());
      this.mediaStream = null;
    }

    this.liveVideo.style.display = 'none';
    this.simCanvas.style.display = 'block';
    this.noSignalOverlay.classList.add('hidden');

    this.statusDot.className = 'status-indicator connected';
    this.osdStatusText.textContent = 'SIMULATOR 5.8G';

    this.isSimActive = true;
    let roll = 0;
    let pitch = 0;
    let altitude = 120;
    let speed = 45;

    const renderSimFrame = (timestamp) => {
      if (!this.isSimActive) return;

      const w = this.simCanvas.width;
      const h = this.simCanvas.height;
      const ctx = this.simCtx;

      // Simulate gentle drone flight dynamics
      roll = Math.sin(timestamp / 1200) * 18;
      pitch = Math.cos(timestamp / 1600) * 12;
      altitude += Math.sin(timestamp / 2000) * 0.4;
      speed = 45 + Math.sin(timestamp / 1000) * 5;

      ctx.save();
      ctx.clearRect(0, 0, w, h);

      // Rotate canvas for artificial horizon banking
      ctx.translate(w / 2, h / 2);
      ctx.rotate((roll * Math.PI) / 180);
      ctx.translate(-w / 2, -h / 2);

      const horizonY = h / 2 + pitch * 3;

      // Sky Gradient
      const skyGrad = ctx.createLinearGradient(0, 0, 0, horizonY);
      skyGrad.addColorStop(0, '#0d2b45');
      skyGrad.addColorStop(1, '#203c56');
      ctx.fillStyle = skyGrad;
      ctx.fillRect(-w, -h, w * 3, horizonY + h);

      // Ground Gradient
      const groundGrad = ctx.createLinearGradient(0, horizonY, 0, h);
      groundGrad.addColorStop(0, '#1c3422');
      groundGrad.addColorStop(1, '#101d14');
      ctx.fillStyle = groundGrad;
      ctx.fillRect(-w, horizonY, w * 3, h * 3);

      // Horizon line
      ctx.strokeStyle = '#00E5FF';
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.moveTo(-w, horizonY);
      ctx.lineTo(w * 2, horizonY);
      ctx.stroke();

      ctx.restore();

      // Pitch ladder shift
      this.pitchLadder.style.transform = `translateY(${pitch * 2}px) rotate(${roll}deg)`;

      // Synthetic Analog VTX Static noise
      if (this.chkStaticNoise.checked || Math.random() < 0.05) {
        ctx.fillStyle = 'rgba(255, 255, 255, 0.08)';
        for (let i = 0; i < 40; i++) {
          ctx.fillRect(Math.random() * w, Math.random() * h, Math.random() * 80 + 20, 1.5);
        }
      }

      this.fpsCounter++;

      // VR Sync
      if (this.isVrMode) {
        this.vrCtxLeft.drawImage(this.simCanvas, 0, 0, w, h);
        this.vrCtxRight.drawImage(this.simCanvas, 0, 0, w, h);
      }

      this.simAnimationId = requestAnimationFrame(renderSimFrame);
    };

    this.simAnimationId = requestAnimationFrame(renderSimFrame);
  }

  stopSimulator() {
    this.isSimActive = false;
    if (this.simAnimationId) {
      cancelAnimationFrame(this.simAnimationId);
      this.simAnimationId = null;
    }
  }

  startVrRenderLoop() {
    const vrLoop = () => {
      if (!this.isVrMode || this.isSimActive) return;
      if (this.liveVideo.readyState >= 2) {
        const w = this.vrCanvasLeft.width;
        const h = this.vrCanvasLeft.height;
        this.vrCtxLeft.drawImage(this.liveVideo, 0, 0, w, h);
        this.vrCtxRight.drawImage(this.liveVideo, 0, 0, w, h);
      }
      this.fpsCounter++;
      requestAnimationFrame(vrLoop);
    };
    if (this.isVrMode) requestAnimationFrame(vrLoop);
  }

  toggleVrMode() {
    this.isVrMode = !this.isVrMode;
    if (this.isVrMode) {
      this.singleViewport.classList.remove('active');
      this.vrViewport.classList.add('active');
      this.btnToggleVr.classList.add('active');
      this.startVrRenderLoop();
    } else {
      this.vrViewport.classList.remove('active');
      this.singleViewport.classList.add('active');
      this.btnToggleVr.classList.remove('active');
    }
  }

  cycleAspectRatio() {
    this.aspectIndex = (this.aspectIndex + 1) % this.aspectRatios.length;
    const mode = this.aspectRatios[this.aspectIndex];
    this.viewportWrapper.setAttribute('data-aspect', mode);

    const labels = {
      '4-3': '4:3 NATIVE',
      '16-9': '16:9 WIDE',
      'fill': 'FULL STRETCH'
    };
    this.osdAspect.textContent = labels[mode];
  }

  updateFps() {
    const now = performance.now();
    const delta = (now - this.lastFpsCheck) / 1000;
    const fps = Math.round(this.fpsCounter / delta);
    this.osdFps.textContent = `${fps} FPS`;
    this.fpsCounter = 0;
    this.lastFpsCheck = now;
  }

  // ==================== DVR Video Recording ====================

  toggleRecording() {
    if (this.mediaRecorder && this.mediaRecorder.state === 'recording') {
      this.stopRecording();
    } else {
      this.startRecording();
    }
  }

  startRecording() {
    let streamToRecord = null;

    if (this.isSimActive) {
      streamToRecord = this.simCanvas.captureStream(30);
    } else if (this.mediaStream) {
      streamToRecord = this.mediaStream;
    } else {
      alert('Please connect a UVC receiver or start the simulator before recording.');
      return;
    }

    try {
      this.recordedChunks = [];
      const options = { mimeType: 'video/webm;codecs=vp8,opus' };
      if (!MediaRecorder.isTypeSupported(options.mimeType)) {
        options.mimeType = 'video/webm';
      }

      this.mediaRecorder = new MediaRecorder(streamToRecord, options);

      this.mediaRecorder.ondataavailable = (e) => {
        if (e.data.size > 0) this.recordedChunks.push(e.data);
      };

      this.mediaRecorder.onstop = () => {
        this.finalizeRecording();
      };

      this.mediaRecorder.start(1000);
      this.recordStartTime = Date.now();

      this.btnRecord.classList.add('recording');
      this.osdRecPill.style.display = 'inline-flex';

      this.recordTimerInterval = setInterval(() => {
        const elapsedSec = Math.floor((Date.now() - this.recordStartTime) / 1000);
        const hrs = String(Math.floor(elapsedSec / 3600)).padStart(2, '0');
        const mins = String(Math.floor((elapsedSec % 3600) / 60)).padStart(2, '0');
        const secs = String(elapsedSec % 60).padStart(2, '0');
        this.osdRecTimer.textContent = `${hrs}:${mins}:${secs}`;
      }, 500);

    } catch (e) {
      console.error('Failed to start MediaRecorder:', e);
      alert('Recording error: ' + e.message);
    }
  }

  stopRecording() {
    if (this.mediaRecorder && this.mediaRecorder.state === 'recording') {
      this.mediaRecorder.stop();
      clearInterval(this.recordTimerInterval);
      this.btnRecord.classList.remove('recording');
      this.osdRecPill.style.display = 'none';
      this.osdRecTimer.textContent = '00:00:00';
    }
  }

  finalizeRecording() {
    const blob = new Blob(this.recordedChunks, { type: 'video/webm' });
    const url = URL.createObjectURL(blob);
    const dateStr = new Date().toISOString().replace(/[:.]/g, '-');
    const fileName = `SKYFPV_DVR_${dateStr}.webm`;

    // Download to disk automatically
    const a = document.createElement('a');
    a.href = url;
    a.download = fileName;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);

    // Save to in-app gallery list
    this.savedFlights.unshift({
      type: 'video',
      url: url,
      name: fileName,
      size: (blob.size / (1024 * 1024)).toFixed(2) + ' MB',
      date: new Date().toLocaleTimeString()
    });

    this.renderGallery();
  }

  // ==================== Snapshots ====================

  takeSnapshot() {
    const snapCanvas = document.createElement('canvas');
    snapCanvas.width = 640;
    snapCanvas.height = 480;
    const snapCtx = snapCanvas.getContext('2d');

    if (this.isSimActive) {
      snapCtx.drawImage(this.simCanvas, 0, 0);
    } else if (this.mediaStream && this.liveVideo.readyState >= 2) {
      snapCtx.drawImage(this.liveVideo, 0, 0, 640, 480);
    } else {
      alert('No video stream available to capture photo.');
      return;
    }

    const dateStr = new Date().toISOString().replace(/[:.]/g, '-');
    const fileName = `SKYFPV_SNAP_${dateStr}.png`;

    snapCanvas.toBlob((blob) => {
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = fileName;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);

      this.savedFlights.unshift({
        type: 'photo',
        url: url,
        name: fileName,
        size: (blob.size / 1024).toFixed(1) + ' KB',
        date: new Date().toLocaleTimeString()
      });

      this.renderGallery();
    });
  }

  // ==================== Gallery Modal ====================

  openGallery() {
    this.renderGallery();
    this.galleryModal.classList.add('open');
  }

  renderGallery() {
    this.galleryGrid.innerHTML = '';
    this.galleryCount.textContent = `${this.savedFlights.length} files`;

    if (this.savedFlights.length === 0) {
      this.galleryEmpty.style.display = 'block';
      this.galleryGrid.style.display = 'none';
      return;
    }

    this.galleryEmpty.style.display = 'none';
    this.galleryGrid.style.display = 'grid';

    this.savedFlights.forEach((flight, idx) => {
      const card = document.createElement('div');
      card.className = 'gallery-card';

      const previewContainer = document.createElement('div');
      previewContainer.className = 'card-media-preview';

      if (flight.type === 'video') {
        const vid = document.createElement('video');
        vid.src = flight.url;
        vid.controls = true;
        vid.preload = 'metadata';
        previewContainer.appendChild(vid);
      } else {
        const img = document.createElement('img');
        img.src = flight.url;
        previewContainer.appendChild(img);
      }

      const info = document.createElement('div');
      info.className = 'card-info';
      info.innerHTML = `
        <div class="card-meta">
          <div>${flight.type === 'video' ? '🎬 VIDEO' : '📸 PHOTO'}</div>
          <div>${flight.size} • ${flight.date}</div>
        </div>
        <button class="btn-card-action" title="Download">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3"/></svg>
        </button>
      `;

      info.querySelector('.btn-card-action').addEventListener('click', () => {
        const a = document.createElement('a');
        a.href = flight.url;
        a.download = flight.name;
        a.click();
      });

      card.appendChild(previewContainer);
      card.appendChild(info);
      this.galleryGrid.appendChild(card);
    });
  }
}

// Initialize on page load
window.addEventListener('DOMContentLoaded', () => {
  window.skyFpv = new SkyFpvApp();
});
