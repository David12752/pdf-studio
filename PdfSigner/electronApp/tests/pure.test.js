const test = require('node:test');
const assert = require('node:assert/strict');
const {
  parsePageList,
  parseSessionIdFromDeepLink,
  computePdfPlacement,
  transitionRemoteStatus,
} = require('../renderer/core/pure');

test('parsePageList returns all pages when input empty', () => {
  assert.deepEqual(parsePageList('', 4), [1, 2, 3, 4]);
});

test('parsePageList filters invalid and duplicates', () => {
  assert.deepEqual(parsePageList('1,2,2,0,8,3', 5), [1, 2, 3]);
});

test('parseSessionIdFromDeepLink supports main route', () => {
  assert.equal(parseSessionIdFromDeepLink('pdfsigner://sign/ABC123DEF456'), 'ABC123DEF456');
});

test('parseSessionIdFromDeepLink supports query fallback', () => {
  assert.equal(parseSessionIdFromDeepLink('pdfsigner://sign?sessionId=ZXCV1234'), 'ZXCV1234');
});

test('parseSessionIdFromDeepLink rejects malformed ids', () => {
  assert.equal(parseSessionIdFromDeepLink('pdfsigner://sign/<script>'), null);
  assert.equal(parseSessionIdFromDeepLink('https://example.com/x'), null);
});

test('computePdfPlacement transforms ratio space to pdf coordinates', () => {
  const rect = computePdfPlacement(1000, 2000, {
    xRatio: 0.1,
    yRatio: 0.2,
    wRatio: 0.3,
    hRatio: 0.1,
  });

  assert.deepEqual(rect, {
    x: 100,
    y: 1400,
    drawWidth: 300,
    drawHeight: 200,
  });
});

test('transitionRemoteStatus follows allowed transitions only', () => {
  assert.equal(transitionRemoteStatus('PENDING', 'view'), 'VIEWED');
  assert.equal(transitionRemoteStatus('VIEWED', 'sign'), 'SIGNED');
  assert.equal(transitionRemoteStatus('SIGNED', 'view'), 'SIGNED');
});
