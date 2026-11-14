/**
 * PhonePC Pair - Remote PC Input Controller (Phase 3 Foundation)
 * Safe handling of mouse moves, clicks, scrolls, and keystrokes via nut-js with rate limiting and coordinate clamping.
 */

let nut = null;
try {
  nut = require('@nut-tree-fork/nut-js');
  // Configure nut-js speed
  if (nut && nut.mouse) {
    nut.mouse.config.autoDelayMs = 0;
  }
} catch (err) {
  console.warn('[Control] @nut-tree-fork/nut-js not natively compiled or available. Using stub mode.', err.message);
}

class PcControlManager {
  constructor() {
    this.allowed = false; // Off by default! Explicit user toggle required
    this.lastInputTime = 0;
    this.minInputIntervalMs = 15; // Rate limit (~66 events/sec)
    this.screenWidth = 1920;
    this.screenHeight = 1080;
    this.isKillSwitched = false;
    // Sub-pixel remainder from relative motion: tiny finger moves accumulate
    // until they are worth one real pixel instead of being rounded away.
    this._accX = 0;
    this._accY = 0;
    try {
      const { screen } = require('electron');
      const d = screen.getPrimaryDisplay();
      this.setScreenBounds(d.size.width, d.size.height);
    } catch (_) { /* not running inside Electron */ }
  }

  setScreenBounds(width, height) {
    if (width > 0 && height > 0) {
      this.screenWidth = width;
      this.screenHeight = height;
    }
  }

  setAllowed(allowed) {
    this.allowed = !!allowed;
    if (!this.allowed) {
      this.isKillSwitched = false;
    }
  }

  triggerKillSwitch() {
    this.isKillSwitched = true;
    this.allowed = false;
    console.log('[Control] KILL SWITCH ACTIVATED! Remote control disabled.');
  }

  canAcceptInput() {
    if (!this.allowed || this.isKillSwitched) {
      return false;
    }
    const now = Date.now();
    if (now - this.lastInputTime < this.minInputIntervalMs) {
      return false; // Throttle
    }
    this.lastInputTime = now;
    return true;
  }

  /**
   * Relative pointer motion from the phone's trackpad.
   *
   * Like a real touchpad: the delta the finger travelled moves the cursor the
   * same way, it never teleports to an absolute spot. A gentle speed curve
   * keeps small movements precise while fast flicks cover the screen, and the
   * sub-pixel remainder is carried into the next event so slow drags do not
   * stutter. No throttle here: a 15 ms gate was adding up to a frame of lag to
   * every move while the phone was already capping the event rate.
   */
  async handleMouseMove(dx, dy, accel = true) {
    if (!this.allowed || this.isKillSwitched || !nut) return;
    try {
      const ax = Number(dx) || 0;
      const ay = Number(dy) || 0;
      if (!ax && !ay) return;
      const speed = Math.hypot(ax, ay);
      const gain = accel === false ? 1 : Math.min(2.4, 1 + speed / 160);
      this._accX += ax * gain;
      this._accY += ay * gain;
      const stepX = Math.trunc(this._accX);
      const stepY = Math.trunc(this._accY);
      if (!stepX && !stepY) return;
      this._accX -= stepX;
      this._accY -= stepY;
      const pos = await nut.mouse.getPosition();
      const targetX = Math.max(0, Math.min(this.screenWidth - 1, pos.x + stepX));
      const targetY = Math.max(0, Math.min(this.screenHeight - 1, pos.y + stepY));
      if (targetX === pos.x && targetY === pos.y) return;
      await nut.mouse.setPosition(new nut.Point(targetX, targetY));
    } catch (err) {
      console.error('[Control] Mouse move error:', err);
    }
  }

  /**
   * Absolute pointing from the remote-PC-screen view: normalized (0..1)
   * coordinates mapped to the primary display. Used when the phone sees the
   * real desktop and taps should land where they tapped.
   */
  async handleMouseMoveAbs(x, y) {
    if (!this.canAcceptInput() || !nut) return;
    try {
      const nx = Math.max(0, Math.min(1, Number(x)));
      const ny = Math.max(0, Math.min(1, Number(y)));
      const targetX = Math.round(nx * (this.screenWidth - 1));
      const targetY = Math.round(ny * (this.screenHeight - 1));
      await nut.mouse.setPosition(new nut.Point(targetX, targetY));
    } catch (err) {
      console.error('[Control] Absolute mouse move error:', err);
    }
  }

  async handleMouseClick(button = 'left', double = false) {
    if (!this.allowed || this.isKillSwitched || !nut) return;
    try {
      const nutBtn = button === 'right' ? nut.Button.RIGHT : button === 'middle' ? nut.Button.MIDDLE : nut.Button.LEFT;
      if (double) {
        await nut.mouse.doubleClick(nutBtn);
      } else {
        await nut.mouse.click(nutBtn);
      }
    } catch (err) {
      console.error('[Control] Mouse click error:', err);
    }
  }

  async handleMouseScroll(deltaY) {
    if (!this.canAcceptInput() || !nut) return;
    try {
      if (deltaY > 0) {
        await nut.mouse.scrollDown(Math.abs(deltaY));
      } else {
        await nut.mouse.scrollUp(Math.abs(deltaY));
      }
    } catch (err) {
      console.error('[Control] Mouse scroll error:', err);
    }
  }

  /**
   * Press-and-hold / release a mouse button so the remote screen view can
   * drag (PC_MOUSE_DOWN -> absolute moves -> PC_MOUSE_UP).
   */
  async handleMouseButton(action, button = 'left') {
    if (!this.allowed || this.isKillSwitched || !nut) return;
    const btn = button === 'right' ? nut.Button.RIGHT : button === 'middle' ? nut.Button.MIDDLE : nut.Button.LEFT;
    try {
      if (action === 'down') await nut.mouse.pressButton(btn);
      else await nut.mouse.releaseButton(btn);
    } catch (err) {
      console.error('[Control] Mouse button error:', err);
    }
  }

  /** Presses and releases a key combination, e.g. ['LeftControl','C']. Names must exist in nut.Key. */
  async pressKeys(names) {
    if (!this.allowed || this.isKillSwitched || !nut) return false;
    try {
      const keys = names.map((n) => nut.Key[n]);
      if (keys.some((k) => k === undefined)) return false;
      await nut.keyboard.pressKey(...keys);
      await nut.keyboard.releaseKey(...keys.slice().reverse());
      return true;
    } catch (err) {
      console.error('[Control] pressKeys error:', err);
      return false;
    }
  }

  async handleKeyInput(text, specialKey) {
    if (!this.allowed || this.isKillSwitched || !nut) return;
    try {
      if (text) {
        // Nut.js keyboard.type
        await nut.keyboard.type(text);
      } else if (specialKey) {
        // Map common special keys
        const keyMap = {
          'ENTER': nut.Key.Enter,
          'BACKSPACE': nut.Key.Backspace,
          'ESCAPE': nut.Key.Escape,
          'TAB': nut.Key.Tab,
          'SPACE': nut.Key.Space,
          'UP': nut.Key.Up,
          'DOWN': nut.Key.Down,
          'LEFT': nut.Key.Left,
          'RIGHT': nut.Key.Right,
          'HOME': nut.Key.Home,
          'END': nut.Key.End,
          'DELETE': nut.Key.Delete,
          'INSERT': nut.Key.Insert,
          'PAGEUP': nut.Key.PageUp,
          'PAGEDOWN': nut.Key.PageDown
        };
        const mapped = keyMap[specialKey.toUpperCase()];
        if (mapped) {
          await nut.keyboard.pressKey(mapped);
          await nut.keyboard.releaseKey(mapped);
        }
      }
    } catch (err) {
      console.error('[Control] Key input error:', err);
    }
  }

  async handleMediaAction(action) {
    if (!this.allowed || this.isKillSwitched) return;
    try {
      const { exec } = require('child_process');
      const actionUpper = (action || '').toUpperCase();
      let keyChar = null;
      if (actionUpper === 'VOLUME_UP') keyChar = 175;
      else if (actionUpper === 'VOLUME_DOWN') keyChar = 174;
      else if (actionUpper === 'VOLUME_MUTE') keyChar = 173;
      else if (actionUpper === 'PLAY_PAUSE') keyChar = 179;
      else if (actionUpper === 'NEXT') keyChar = 176;
      else if (actionUpper === 'PREV') keyChar = 177;

      if (keyChar && process.platform === 'win32') {
        exec(`powershell -NoProfile -Command "(New-Object -ComObject WScript.Shell).SendKeys([char]${keyChar})"`);
      }
    } catch (err) {
      console.error('[Control] Media action error:', err);
    }
  }

  handleSystemAction(action) {
    if (!this.allowed || this.isKillSwitched) return;
    try {
      const { execFile } = require('child_process');
      const actionUpper = (action || '').toUpperCase();
      if (actionUpper === 'LOCK_PC' && process.platform === 'win32') {
        execFile('rundll32.exe', ['user32.dll,LockWorkStation']);
      }
    } catch (err) {
      console.error('[Control] System action error:', err);
    }
  }
}

module.exports = { PcControlManager };

