// site/main.js — landing page interactivity
// Three named init functions. No framework. No bundler. ES2020 syntax.

/* tab switcher -------------------------------------------------------- */
function initTabSwitcher() {
  const tablists = document.querySelectorAll('[role="tablist"]');
  tablists.forEach((tablist) => {
    const tabs = Array.from(tablist.querySelectorAll('[role="tab"]'));
    if (tabs.length === 0) return;

    const select = (tab) => {
      tabs.forEach((t) => {
        const isSelected = t === tab;
        t.setAttribute('aria-selected', String(isSelected));
        t.setAttribute('tabindex', isSelected ? '0' : '-1');
        const panel = document.getElementById(t.getAttribute('aria-controls'));
        if (!panel) return;
        if (isSelected) {
          panel.removeAttribute('hidden');
          panel.setAttribute('data-active', '');
        } else {
          panel.setAttribute('hidden', '');
          panel.removeAttribute('data-active');
        }
      });
    };

    tablist.addEventListener('click', (event) => {
      const tab = event.target.closest('[role="tab"]');
      if (!tab || !tabs.includes(tab)) return;
      select(tab);
      tab.focus();
    });

    tablist.addEventListener('keydown', (event) => {
      const currentIndex = tabs.findIndex((t) => t === document.activeElement);
      if (currentIndex === -1) return;
      let nextIndex = null;
      if (event.key === 'ArrowRight') nextIndex = (currentIndex + 1) % tabs.length;
      if (event.key === 'ArrowLeft')  nextIndex = (currentIndex - 1 + tabs.length) % tabs.length;
      if (event.key === 'Home')       nextIndex = 0;
      if (event.key === 'End')        nextIndex = tabs.length - 1;
      if (nextIndex === null) return;
      event.preventDefault();
      const next = tabs[nextIndex];
      select(next);
      next.focus();
    });
  });
}

document.addEventListener('DOMContentLoaded', () => {
  initTabSwitcher();
});
