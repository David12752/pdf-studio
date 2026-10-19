(function (globalScope) {
  function createPdfToolsWorkerClient() {
    const worker = new Worker('./workers/pdfToolsWorker.js');
    let seq = 0;
    const pending = new Map();

    worker.onmessage = (event) => {
      const { id, ok, result, error } = event.data || {};
      const handlers = pending.get(id);
      if (!handlers) return;
      pending.delete(id);
      if (ok) handlers.resolve(result);
      else handlers.reject(new Error(error || 'Worker task failed'));
    };

    function run(task, payload) {
      return new Promise((resolve, reject) => {
        const id = ++seq;
        pending.set(id, { resolve, reject });
        worker.postMessage({ id, task, payload });
      });
    }

    return {
      merge: (filesBase64) => run('merge', { filesBase64 }),
      split: (sourceBase64, pages) => run('split', { sourceBase64, pages }),
      extract: (sourceBase64, pageNum) => run('extract', { sourceBase64, pageNum }),
      terminate: () => worker.terminate(),
    };
  }

  const api = { createPdfToolsWorkerClient };

  if (typeof module !== 'undefined' && module.exports) {
    module.exports = api;
  }

  globalScope.PdfStudioWorkers = api;
})(typeof window !== 'undefined' ? window : globalThis);
