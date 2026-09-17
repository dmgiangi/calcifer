(() => {
  const passwordLogin = document.querySelector('#password-login');
  const passwordForm = document.querySelector('#password-form');
  const passwordError = document.querySelector('#password-error');

  if (window.location.protocol === 'file:') {
    passwordLogin.hidden = false;
    passwordForm.addEventListener('submit', (event) => event.preventDefault());
    passwordError.textContent = 'Preview mode: sign-in is available when served by the authorization server.';
    passwordError.hidden = false;
    return;
  }

  const getJson = async (url) => {
    const response = await fetch(url, {
      credentials: 'same-origin',
      headers: {Accept: 'application/json'}
    });
    if (!response.ok) {
      throw new Error(`Request failed: ${response.status}`);
    }
    return response.json();
  };

  const loadPasswordFallback = async () => {
    const config = await getJson('login/config');
    if (!config.passwordEnabled) {
      return;
    }

    const csrf = await getJson('login/csrf');
    const csrfInput = document.createElement('input');
    csrfInput.type = 'hidden';
    csrfInput.name = csrf.parameterName;
    csrfInput.value = csrf.token;
    passwordForm.prepend(csrfInput);
    passwordLogin.hidden = false;
  };

  loadPasswordFallback().catch(() => {
    passwordError.hidden = false;
  });
})();
