/**
 * Auth Feature — PDF Studio Desktop
 * Login/Register overlay + user state management.
 * Communicates with main process via nativeApi (preload bridge).
 */
function createAuthFeature({ $, toast, nativeApi }) {
  let currentUser = null;
  let onAuthReady = null;

  /* ── DOM refs ───────────────────────────────────────────────────── */
  const overlay      = $('authOverlay');
  const loginForm    = $('authLoginForm');
  const registerForm = $('authRegisterForm');
  const btnShowLogin    = $('authShowLogin');
  const btnShowRegister = $('authShowRegister');
  const btnGuest        = $('authGuestBtn');
  const loginEmail      = $('authLoginEmail');
  const loginPass       = $('authLoginPassword');
  const btnLogin        = $('authLoginBtn');
  const regEmail        = $('authRegEmail');
  const regPass         = $('authRegPassword');
  const regPassConfirm  = $('authRegPasswordConfirm');
  const btnRegister     = $('authRegisterBtn');
  const authError       = $('authError');
  const userInfo        = $('sidebarUserInfo');
  const userEmail       = $('sidebarUserEmail');
  const btnLogout       = $('sidebarLogoutBtn');

  function showError(msg) {
    if (authError) { authError.textContent = msg; authError.style.display = 'block'; }
  }
  function clearError() {
    if (authError) { authError.textContent = ''; authError.style.display = 'none'; }
  }

  function showLogin() {
    clearError();
    if (loginForm)    loginForm.style.display = '';
    if (registerForm) registerForm.style.display = 'none';
    if (btnShowLogin)    btnShowLogin.classList.add('active');
    if (btnShowRegister) btnShowRegister.classList.remove('active');
  }

  function showRegister() {
    clearError();
    if (loginForm)    loginForm.style.display = 'none';
    if (registerForm) registerForm.style.display = '';
    if (btnShowLogin)    btnShowLogin.classList.remove('active');
    if (btnShowRegister) btnShowRegister.classList.add('active');
  }

  function hideOverlay() {
    if (overlay) overlay.style.display = 'none';
  }

  function showOverlay() {
    if (overlay) overlay.style.display = '';
  }

  function updateSidebar() {
    if (!userInfo) return;
    if (currentUser) {
      userInfo.style.display = '';
      if (userEmail) userEmail.textContent = currentUser.email || 'Guest';
      if (btnLogout) btnLogout.style.display = '';
    } else {
      userInfo.style.display = 'none';
    }
  }

  async function handleLogin() {
    clearError();
    const email = loginEmail?.value?.trim();
    const pass  = loginPass?.value;
    if (!email || !pass) return showError('Please enter email and password');
    if (btnLogin) btnLogin.disabled = true;
    try {
      const result = await nativeApi.cloudLogin(email, pass);
      if (!result.ok) return showError(result.error || 'Login failed');
      currentUser = result.user;
      updateSidebar();
      hideOverlay();
      toast('Logged in successfully');
      if (onAuthReady) onAuthReady(currentUser);
    } catch (err) {
      showError(err.message || 'Login error');
    } finally {
      if (btnLogin) btnLogin.disabled = false;
    }
  }

  async function handleRegister() {
    clearError();
    const email   = regEmail?.value?.trim();
    const pass    = regPass?.value;
    const confirm = regPassConfirm?.value;
    if (!email || !pass) return showError('Please enter email and password');
    if (pass !== confirm) return showError('Passwords do not match');
    if (pass.length < 12) return showError('Password must be at least 12 characters');
    if (btnRegister) btnRegister.disabled = true;
    try {
      const result = await nativeApi.cloudRegister(email, pass);
      if (!result.ok) return showError(result.error || 'Registration failed');
      currentUser = result.user;
      updateSidebar();
      hideOverlay();
      toast('Account created successfully');
      if (onAuthReady) onAuthReady(currentUser, true); // true = new user
    } catch (err) {
      showError(err.message || 'Registration error');
    } finally {
      if (btnRegister) btnRegister.disabled = false;
    }
  }

  function handleGuest() {
    currentUser = { uid: 'guest', email: 'Guest', isGuest: true };
    updateSidebar();
    hideOverlay();
    toast('Continuing as guest (local only)');
    if (onAuthReady) onAuthReady(currentUser);
  }

  async function handleLogout() {
    try {
      await nativeApi.cloudLogout();
    } catch (_) { /* ignore */ }
    currentUser = null;
    updateSidebar();
    showOverlay();
    showLogin();
  }

  function wireEvents() {
    if (btnShowLogin)    btnShowLogin.addEventListener('click', showLogin);
    if (btnShowRegister) btnShowRegister.addEventListener('click', showRegister);
    if (btnGuest)        btnGuest.addEventListener('click', handleGuest);
    if (btnLogin)        btnLogin.addEventListener('click', handleLogin);
    if (btnRegister)     btnRegister.addEventListener('click', handleRegister);
    if (btnLogout)       btnLogout.addEventListener('click', handleLogout);

    // Enter key on forms
    if (loginPass) loginPass.addEventListener('keydown', (e) => { if (e.key === 'Enter') handleLogin(); });
    if (regPassConfirm) regPassConfirm.addEventListener('keydown', (e) => { if (e.key === 'Enter') handleRegister(); });
  }

  async function init(authReadyCallback) {
    onAuthReady = authReadyCallback;
    wireEvents();

    // Check if Firebase is configured
    let configured = false;
    try { configured = await nativeApi.cloudIsConfigured(); } catch (_) {}

    if (configured) {
      // Check if user is already logged in
      const user = await nativeApi.cloudGetUser();
      if (user) {
        currentUser = user;
        updateSidebar();
        hideOverlay();
        if (onAuthReady) onAuthReady(currentUser);
        return;
      }
      // Show full login overlay (login + register + guest)
      showOverlay();
      showLogin();
    } else {
      // Firebase not configured — still show overlay but hide login/register,
      // show only welcome + guest button with note
      if (loginForm)    loginForm.style.display = 'none';
      if (registerForm) registerForm.style.display = 'none';
      const tabsEl = overlay?.querySelector('.auth-tabs');
      if (tabsEl) tabsEl.style.display = 'none';
      const dividerEl = overlay?.querySelector('.auth-divider');
      if (dividerEl) dividerEl.style.display = 'none';
      // Update subtitle with offline note
      const subtitle = overlay?.querySelector('.auth-subtitle');
      if (subtitle) subtitle.textContent = 'Welcome! Cloud sync not configured yet — you can set it up later in Settings.';
      showOverlay();
    }
  }

  function getUser() {
    return currentUser;
  }

  function isLoggedIn() {
    return currentUser && !currentUser.isGuest;
  }

  return { init, getUser, isLoggedIn, handleLogout };
}

window.PdfStudioAuth = { createAuthFeature };
