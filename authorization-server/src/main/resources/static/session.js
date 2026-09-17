(() => {
  const form = document.querySelector('#logout-form');
  const error = document.querySelector('#logout-error');

  fetch('/login/csrf', {
    credentials: 'same-origin',
    headers: {Accept: 'application/json'}
  })
      .then((response) => {
        if (!response.ok) {
          throw new Error(`Request failed: ${response.status}`);
        }
        return response.json();
      })
      .then((csrf) => {
        const input = document.createElement('input');
        input.type = 'hidden';
        input.name = csrf.parameterName;
        input.value = csrf.token;
        form.prepend(input);
        form.hidden = false;
      })
      .catch(() => {
        error.hidden = false;
      });
})();
