/* Deliberately ES5: this remains visible even when the application cannot parse. */
(function () {
  var done = false;
  var timer = setTimeout(function () { fail('启动未完成。请重试；若仍无法打开，请向管理员提供下方系统网页组件信息。'); }, 15000);
  function fail(message) {
    if (done) return;
    document.getElementById('startup-status').textContent = '应用启动失败';
    document.getElementById('startup-details').textContent = message + '\n' + navigator.userAgent;
    document.getElementById('startup-retry').hidden = false;
    clearTimeout(timer);
  }
  window.mesStartup = {
    ready: function () { done = true; clearTimeout(timer); document.getElementById('startup').style.display = 'none'; },
    fail: fail
  };
  window.addEventListener('error', function (event) { fail(event.message || '页面资源加载失败，请重新打开。'); });
  window.addEventListener('unhandledrejection', function (event) { fail(event.reason && event.reason.message || '启动过程中发生异常。'); });
})();
