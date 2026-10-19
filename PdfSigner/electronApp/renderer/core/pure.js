(function (globalScope) {
  function parsePageList(input, maxPages) {
    const raw = String(input || '').trim();
    if (!raw) return [...Array(maxPages).keys()].map((i) => i + 1);
    const pages = raw
      .split(',')
      .map((v) => Number(v.trim()))
      .filter((v) => Number.isInteger(v) && v >= 1 && v <= maxPages);
    return [...new Set(pages)];
  }

  function parseSessionIdFromDeepLink(link) {
    if (!link || !String(link).toLowerCase().startsWith('pdfsigner://')) return null;
    try {
      const url = new URL(link);
      let candidate = null;
      if (url.hostname === 'sign') {
        const fromPath = (url.pathname || '').replace(/^\/+/, '').trim();
        candidate = fromPath || (url.searchParams.get('sessionId') || '').trim();
      } else if ((url.pathname || '').toLowerCase().startsWith('/sign/')) {
        candidate = (url.pathname || '').replace(/^\/sign\//i, '').trim();
      }

      if (!candidate) return null;
      if (!/^[A-Za-z0-9_-]{6,64}$/.test(candidate)) return null;
      return candidate;
    } catch {
      const parts = String(link).split('/').filter(Boolean);
      const candidate = parts.length ? parts[parts.length - 1] : '';
      if (!candidate || !/^[A-Za-z0-9_-]{6,64}$/.test(candidate)) return null;
      return candidate;
    }
  }

  function computePdfPlacement(width, height, placement) {
    const x = placement.xRatio * width;
    const y = height - ((placement.yRatio + placement.hRatio) * height);
    const drawWidth = placement.wRatio * width;
    const drawHeight = placement.hRatio * height;
    return { x, y, drawWidth, drawHeight };
  }

  function transitionRemoteStatus(currentStatus, action) {
    const transitions = {
      PENDING: { view: 'VIEWED', sign: 'SIGNED', cancel: 'CANCELLED', expire: 'EXPIRED' },
      VIEWED: { sign: 'SIGNED', cancel: 'CANCELLED', expire: 'EXPIRED' },
      SIGNED: {},
      CANCELLED: {},
      EXPIRED: {},
    };

    const next = transitions[currentStatus]?.[action];
    return next || currentStatus;
  }

  const api = {
    parsePageList,
    parseSessionIdFromDeepLink,
    computePdfPlacement,
    transitionRemoteStatus,
  };

  if (typeof module !== 'undefined' && module.exports) {
    module.exports = api;
  }

  globalScope.PdfStudioPure = api;
})(typeof window !== 'undefined' ? window : globalThis);
