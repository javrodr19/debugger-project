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

/* scroll-triggered reveals ------------------------------------------- */
function initScrollReveals() {
  const targets = document.querySelectorAll('.reveal');
  if (targets.length === 0 || !('IntersectionObserver' in window)) {
    targets.forEach((el) => el.classList.add('is-visible'));
    return;
  }
  const observer = new IntersectionObserver((entries) => {
    entries.forEach((entry) => {
      if (entry.isIntersecting) {
        entry.target.classList.add('is-visible');
        observer.unobserve(entry.target);
      }
    });
  }, { threshold: 0.12, rootMargin: '0px 0px -60px 0px' });
  targets.forEach((el) => observer.observe(el));
}

/* smooth-scroll on anchor click -------------------------------------- */
function initSmoothScroll() {
  document.addEventListener('click', (event) => {
    const link = event.target.closest('a[href^="#"]');
    if (!link) return;
    const href = link.getAttribute('href');
    if (href === '#' || href.length < 2) return;
    const target = document.querySelector(href);
    if (!target) return;
    event.preventDefault();
    target.scrollIntoView({ behavior: 'smooth', block: 'start' });
    if (history.pushState) history.pushState(null, '', href);
  });
}

document.addEventListener('DOMContentLoaded', () => {
  initTabSwitcher();
  initScrollReveals();
  initSmoothScroll();
});
