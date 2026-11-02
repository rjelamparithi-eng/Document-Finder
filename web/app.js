// Frontend for Aram Office Document Management System.
// Handles API calls, UI rendering, PDF viewing/downloading, and version control.
let documents = [];
let stats = {};
let indexRows = [];
let searchType = 'keyword';
let toastTimer;
let currentUser = null;
let csrfToken = '';

const el = (id) => document.getElementById(id);
const safe = (value) => String(value ?? '').replace(/[&<>"']/g, (char) => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[char]));

async function api(url, data = null) {
  let options = {};
  if (data !== null) {
    if (data instanceof FormData) {
      if (!data.has('csrf')) data.append('csrf', csrfToken);
      options = {
        method: 'POST',
        headers: {'X-CSRF-Token': csrfToken},
        body: data
      };
    } else {
      options = {
        method: 'POST',
        headers: {'Content-Type': 'application/x-www-form-urlencoded', 'X-CSRF-Token': csrfToken},
        body: new URLSearchParams(data).toString()
      };
    }
  }
  let response;
  try { response = await fetch(url, options); }
  catch { throw new Error('Cannot connect to Java server. Check the terminal.'); }
  const body = await response.json();
  if (response.status === 401 && url !== '/api/login' && url !== '/api/me') {
    showLogin();
    const loginMsg = el('loginMessage');
    if (loginMsg) {
      loginMsg.className = 'login-error';
      loginMsg.textContent = 'Your session has expired. Please sign in again.';
    }
    throw new Error('Your session has expired. Please sign in again.');
  }
  if (!response.ok || !body.success) throw new Error(body.message || 'Request failed.');
  return body.data;
}

function toast(message, error = false) {
  const box = el('toast');
  box.textContent = message;
  box.className = 'toast show' + (error ? ' error' : '');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => box.className = 'toast', 3500);
}

function page(name) {
  if ((name === 'add' || name === 'activity') && (!currentUser || currentUser.role !== 'HIGHER_OFFICIAL')) {
    toast('Only Higher Official can add documents.', true);
    return;
  }
  document.querySelectorAll('.page').forEach((section) => section.classList.toggle('active', section.id === 'page-' + name));
  document.querySelectorAll('.nav-link').forEach((link) => link.classList.toggle('active', link.dataset.page === name));
  const titles = {dashboard:'Overview',documents:'All Documents',search:'Search Documents',categories:'Departments',versions:'Version History',index:'Keyword Index',add:'Add Document',requests:'Correction Requests',activity:'Activity Log',profile:'My Profile'};
  el('breadcrumb').textContent = titles[name] || name;
  el('sidebar').classList.remove('open');
  window.scrollTo({top:0,behavior:'smooth'});
  if (name === 'index') loadIndex();
  if (name === 'requests') loadRequests();
  if (name === 'activity') loadActivity();
  if (name === 'versions') renderVersionPicker();
  if (name === 'profile') renderProfile();
}

function documentRow(doc) {
  const pdfBadge = doc.hasPdf ? `<span class="tag tag-gold" title="${safe(doc.pdfFilename || 'PDF attached')}">📄 PDF</span> ` : '';
  const recentBadge = doc.isRecent3Months ? `<span class="tag tag-mint" title="Added within the last 3 months">Recent</span> ` : '';
  const createdText = doc.createdAt ? doc.createdAt.split(' ')[0] : 'Uncertain (Legacy)';
  return `<tr><td class="cell-id">${safe(doc.id)}</td><td class="cell-title">${pdfBadge}${recentBadge}${safe(doc.title)}</td><td>${safe(doc.department)}</td><td><span class="tag">${safe(doc.category)}</span></td><td><span class="stars">${'★'.repeat(doc.importance)}</span></td><td><span class="tag tag-blue">V${doc.currentVersion}</span></td><td><small>${safe(createdText)}</small></td><td><button class="tiny-link" data-open-doc="${safe(doc.id)}">View →</button></td></tr>`;
}

function empty(title, subtitle) {
  return `<div class="empty-state">▤<h3>${safe(title)}</h3><p>${safe(subtitle)}</p></div>`;
}

async function refresh() {
  [documents, stats] = await Promise.all([api('/api/documents'), api('/api/stats')]);
  renderDashboard();
  updateFilters();
  renderDocuments();
  renderCategories();
  renderVersionPicker();
  const chooser = el('requestDocumentId');
  if (chooser) {
    const previous = chooser.value;
    chooser.innerHTML = '<option value="">Choose document</option>' + documents.map(d =>
      `<option value="${safe(d.id)}">${safe(d.id)} — ${safe(d.title)}</option>`).join('');
    chooser.value = documents.some(d => d.id === previous) ? previous : '';
  }
}

function renderDashboard() {
  el('statDocuments').textContent = stats.documents;
  el('statDepartments').textContent = stats.departments;
  el('statCategories').textContent = stats.categories;
  el('statKeywords').textContent = stats.keywords;
  el('recentDocuments').innerHTML = documents.slice(0, 6).map((d) => `
    <div class="mini-row" data-open-doc="${safe(d.id)}"><span class="file-badge">${d.hasPdf ? '📄' : '▤'}</span>
      <div class="file-info"><strong>${safe(d.title)}</strong><small>${safe(d.id)} · ${safe(d.department)}${d.hasPdf ? ' · PDF' : ''}</small></div>
      <span class="version-badge">V${d.currentVersion}</span></div>`).join('');
  const depts = Object.entries(stats.departmentCounts || {}).sort((a,b) => a[0].localeCompare(b[0]));
  el('departmentSummary').innerHTML = depts.map(([name, count]) => `<div class="dept-line"><span class="dept-icon">${safe(name.slice(0,1))}</span><strong>${safe(name)}</strong><span class="count-pill">${count} docs</span></div>`).join('');
}

function updateFilters() {
  const dept = el('departmentFilter');
  const cat = el('categoryFilter');
  const selectedDept = dept.value;
  const selectedCat = cat.value;
  const departments = [...new Set(documents.map((d) => d.department))].sort();
  const categories = [...new Set(documents.map((d) => d.category))].sort();
  dept.innerHTML = `<option value="">All Departments</option>` + departments.map((d) => `<option value="${safe(d)}">${safe(d)}</option>`).join('');
  cat.innerHTML = `<option value="">All Categories</option>` + categories.map((d) => `<option value="${safe(d)}">${safe(d)}</option>`).join('');
  dept.value = departments.includes(selectedDept) ? selectedDept : '';
  cat.value = categories.includes(selectedCat) ? selectedCat : '';
}

function renderDocuments() {
  const term = el('documentFilter').value.trim().toLowerCase();
  const dept = el('departmentFilter').value;
  const cat = el('categoryFilter').value;
  const dateF = el('dateFilter') ? el('dateFilter').value : '';
  const matching = documents.filter((d) => (!term || (d.id + ' ' + d.title).toLowerCase().includes(term))
      && (!dept || d.department === dept)
      && (!cat || d.category === cat)
      && (dateF !== '3months' || d.isRecent3Months));
  const countSuffix = dateF === '3months' ? ' (Added in Last 3 Months)' : '';
  el('documentCountLabel').textContent = `Showing ${matching.length} of ${documents.length} documents${countSuffix}`;
  el('documentTable').innerHTML = matching.length ? `<table class="data-table"><thead><tr><th>Document ID</th><th>Title</th><th>Department</th><th>Category</th><th>Importance</th><th>Version</th><th>Date Added</th><th></th></tr></thead><tbody>${matching.map(documentRow).join('')}</tbody></table>`
    : empty('No documents found','Try a different title, department, category or date filter.');
}

function renderCategories() {
  const grouped = {};
  documents.forEach((doc) => {
    if (!grouped[doc.department]) grouped[doc.department] = {};
    if (!grouped[doc.department][doc.category]) grouped[doc.department][doc.category] = [];
    grouped[doc.department][doc.category].push(doc);
  });
  el('categoryGroups').innerHTML = Object.keys(grouped).sort().map((dept) => {
    const groups = grouped[dept];
    const count = Object.values(groups).reduce((total, list) => total + list.length, 0);
    return `<div class="category-box"><div class="category-top"><div><h3>${safe(dept)}</h3><p>${count} documents in ${Object.keys(groups).length} categories</p></div><span class="dept-icon">${safe(dept[0])}</span></div><div class="category-body">${Object.keys(groups).sort().map((cat) =>
      `<details class="category-section"><summary>${safe(cat)} <span class="count-pill">${groups[cat].length}</span></summary><div class="category-docs">${groups[cat].map((doc) =>
        `<div class="category-doc"><button data-open-doc="${safe(doc.id)}">${doc.hasPdf ? '📄 ' : ''}${safe(doc.title)}</button><small>${safe(doc.id)}</small></div>`).join('')}</div></details>`
    ).join('')}</div></div>`;
  }).join('');
}

function renderVersionPicker(selected = '') {
  const chooser = el('versionDocSelect');
  if (!chooser) return;
  const old = selected || chooser.value;
  chooser.innerHTML = `<option value="">Select a document...</option>` + documents.map((d) => `<option value="${safe(d.id)}">${safe(d.id)} — ${safe(d.title)}</option>`).join('');
  chooser.value = documents.some(d => d.id === old) ? old : '';
}

async function loadHistory(id) {
  if (!id) { el('versionArea').innerHTML = empty('Select a document','Its saved versions and update options will appear here.'); return; }
  const doc = documents.find((d) => d.id === id);
  if (!doc) return;
  try {
    const versions = await api('/api/history?id=' + encodeURIComponent(id));
    const isHigher = currentUser && currentUser.role === 'HIGHER_OFFICIAL';

    el('versionArea').innerHTML = `
      <div class="version-info"><strong>${safe(doc.id)} — ${safe(doc.title)}</strong><p>${safe(doc.department)} / ${safe(doc.category)} · Current version: V${doc.currentVersion} · ${versions.length} saved version(s) · Persistent in MySQL</p></div>
      <h3>Saved Versions</h3>
      ${versions.slice().reverse().map((v) => `
        <div class="version-item">
          <span class="version-label">V${v.number}${v.current ? ' · CURRENT' : ''}</span>
          <div class="version-main">
            <h4>${safe(v.title)}</h4>
            <p>${safe(v.content)}</p>
            <small>Keywords: ${safe(v.keywords)}</small>
            ${v.hasPdf ? `<div style="margin-top: 6px;"><a href="/api/pdf?id=${encodeURIComponent(doc.id)}&version=${v.number}" target="_blank" class="button button-primary button-small" style="text-decoration:none; display:inline-block; padding:3px 8px; font-size:12px;">Open PDF (V${v.number}) ↗</a> <a href="/api/pdf?id=${encodeURIComponent(doc.id)}&version=${v.number}&download=1" class="button button-secondary button-small" style="text-decoration:none; display:inline-block; padding:3px 8px; font-size:12px;">Download PDF ⤓</a> <span class="light-note">(${safe(v.pdfFilename || 'PDF')} · ${Math.round((v.pdfSize || 0) / 1024)} KB)</span></div>` : '<div class="light-note" style="margin-top: 4px; font-size: 12px;">No PDF attached to this version</div>'}
          </div>
          ${(isHigher && !v.current) ? `<button class="button button-secondary button-small" data-change-version="${v.number}" data-id="${safe(doc.id)}">Make Current</button>` : ''}
        </div>`).join('')}

      ${isHigher ? `
      <div class="version-form">
        <h3>Add New Version with PDF</h3>
        <form id="addVersionForm">
          <input type="hidden" name="id" value="${safe(doc.id)}">
          <label class="field-label">New Title<input name="title" required maxlength="180" value="${safe(doc.title)}"></label>
          <label class="field-label">New Keywords<input name="keywords" required value="${safe(doc.keywords)}"></label>
          <label class="field-label">Attach PDF Document (Optional, max 5 MB)<input type="file" name="pdf" accept=".pdf,application/pdf"></label>
          <label class="field-label">New Content<textarea name="content" rows="4" required>${safe(doc.content)}</textarea></label>
          <button type="submit" class="button button-primary">Save New Version</button>
        </form>
      </div>` : '<p class="light-note" style="margin-top: 16px;">Lower Officials can view and download document PDFs, but cannot upload or add new versions.</p>'}`;

    if (isHigher) {
      const form = document.getElementById('addVersionForm');
      if (form) {
        form.addEventListener('submit', async (event) => {
          event.preventDefault();
          try {
            const formData = new FormData(form);
            const updated = await api('/api/add-version', formData);
            await refresh();
            renderVersionPicker(updated.id);
            await loadHistory(updated.id);
            toast(`${updated.id} updated to V${updated.currentVersion}. Saved in MySQL; earlier versions and PDFs preserved.`);
          } catch (error) { toast(error.message, true); }
        });
      }
    }
  } catch (error) { toast(error.message, true); }
}

function setSearchType(type) {
  searchType = type;
  document.querySelectorAll('.search-tab').forEach((button) => button.classList.toggle('active', button.dataset.type === type));
  const input = el('searchQuery');
  const messages = {
    keyword:['e.g. water connection application','Keyword search uses HashMap + HashSet. Results are ranked by matching words and importance.'],
    title:['e.g. certificate','Title search checks whether the document title contains your text.'],
    id:['e.g. DOC001','Document ID search finds one specific record using HashMap.']
  };
  input.placeholder = messages[type][0];
  el('searchTip').textContent = messages[type][1];
  el('suggestions').innerHTML = '';
}

let suggestCounter = 0;
async function suggest() {
  const target = el('suggestions');
  target.innerHTML = '';
  if (searchType !== 'keyword') return;
  const input = el('searchQuery').value;
  const prefix = input.split(/[ ,]+/).pop().trim().toLowerCase();
  if (prefix.length < 2) return;
  const request = ++suggestCounter;
  try {
    const words = await api('/api/suggest?prefix=' + encodeURIComponent(prefix));
    if (request !== suggestCounter || !el('searchQuery').value.toLowerCase().endsWith(prefix)) return;
    target.innerHTML = words.slice(0, 8).map((w) => `<button type="button" class="suggestion" data-suggestion="${safe(w)}">${safe(w)}</button>`).join('');
  } catch { /* Suggestions are optional; main search still works. */ }
}

async function runSearch(event) {
  if (event) event.preventDefault();
  const query = el('searchQuery').value.trim();
  if (!query) return;
  try {
    const matches = await api('/api/search?type=' + encodeURIComponent(searchType) + '&q=' + encodeURIComponent(query));
    el('searchResultCount').textContent = `${matches.length} matching document(s)`;
    el('searchResults').innerHTML = matches.length ? matches.map((d,i) => `<article class="result-card"><span class="result-number">${String(i + 1).padStart(2,'0')}</span><div class="result-info"><h3>${d.hasPdf ? '📄 ' : ''}${safe(d.title)}</h3><p>${safe(d.id)} · ${safe(d.department)} · ${safe(d.category)}</p><div class="result-badges">${d.matches ? `<span class="tag">${d.matches} keyword match${d.matches === 1 ? '' : 'es'}</span>` : ''}<span class="tag tag-blue">Importance ${d.importance}/5</span><span class="tag">Current V${d.currentVersion}</span>${d.hasPdf ? '<span class="tag tag-gold">PDF</span>' : ''}</div></div><button class="button button-secondary button-small" data-open-doc="${safe(d.id)}">View Details →</button></article>`).join('')
      : empty('No results found','Try another keyword, document title or ID.');
  } catch (error) { toast(error.message, true); }
}

async function loadIndex() {
  try {
    indexRows = await api('/api/index');
    renderIndex();
  } catch (error) { toast(error.message, true); }
}

function renderIndex() {
  const text = el('keywordFilter').value.trim().toLowerCase();
  const rows = indexRows.filter(row => row.word.includes(text));
  el('keywordCountLabel').textContent = `${rows.length} keywords`;
  el('keywordIndexTable').innerHTML = rows.length ? `<table class="data-table"><thead><tr><th>Keyword</th><th>Matching Document IDs</th><th>Count</th></tr></thead><tbody>${rows.map((r) => `<tr><td class="cell-title">${safe(r.word)}</td><td><div class="keyword-ids">${r.ids.map(id => `<span class="keyword-id">${safe(id)}</span>`).join('')}</div></td><td>${r.ids.length}</td></tr>`).join('')}</tbody></table>` : empty('No keywords found','Try another word.');
}

function showDocument(id) {
  const doc = documents.find((d) => d.id === id);
  if (!doc) { toast('Document not found.', true); return; }
  el('dialogTitle').textContent = doc.title;

  const pdfSection = doc.hasPdf ? `
    <div style="background: #f0f7ff; border: 1px solid #c2dbfe; border-radius: 8px; padding: 12px 16px; margin: 12px 0;">
      <div style="display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:8px;">
        <div>
          <strong style="color:#034aa6;">📄 PDF Document Attached</strong>
          <div style="font-size:13px; color:#555;">${safe(doc.pdfFilename || 'Document.pdf')} (${Math.round((doc.pdfSize || 0) / 1024)} KB)</div>
        </div>
        <div style="display:flex; gap:8px;">
          <a href="/api/pdf?id=${encodeURIComponent(doc.id)}&version=${doc.currentVersion}" target="_blank" class="button button-primary button-small" style="text-decoration:none;">Open PDF ↗</a>
          <a href="/api/pdf?id=${encodeURIComponent(doc.id)}&version=${doc.currentVersion}&download=1" class="button button-secondary button-small" style="text-decoration:none;">Download ⤓</a>
        </div>
      </div>
    </div>` : `<p class="light-note" style="margin: 10px 0;">No PDF file attached to this version.</p>`;

  el('dialogBody').innerHTML = `
    <div class="dialog-content">
      <div class="details-grid">
        <div><span>Document ID</span><strong>${safe(doc.id)}</strong></div>
        <div><span>Current Version</span><strong>Version ${doc.currentVersion} of ${doc.versionCount}</strong></div>
        <div><span>Department</span><strong>${safe(doc.department)}</strong></div>
        <div><span>Category</span><strong>${safe(doc.category)}</strong></div>
        <div><span>Importance</span><strong>${doc.importance} / 5</strong></div>
        <div><span>Date Added</span><strong>${safe(doc.createdAt ? doc.createdAt.split(' ')[0] : 'Uncertain (Legacy record)')}</strong></div>
        <div class="full-span"><span>Keywords</span><strong>${safe(doc.keywords)}</strong></div>
      </div>
      ${pdfSection}
      <h3>Document Content</h3>
      <div class="content-box">${safe(doc.content)}</div>
      <div class="dialog-actions">
        <button class="button button-primary" data-show-history="${safe(doc.id)}">View Version History →</button>
      </div>
    </div>`;

  if (currentUser && currentUser.role === 'LOWER_OFFICIAL') {
    el('dialogBody').querySelector('.dialog-actions').insertAdjacentHTML('beforeend',
      `<button class="button button-secondary" data-request-doc="${safe(doc.id)}">Raise Correction Request</button>`);
  }
  el('documentDialog').showModal();
}

// Page links and document buttons use event delegation.
document.addEventListener('click', async (event) => {
  const pageButton = event.target.closest('[data-page]');
  if (pageButton) { page(pageButton.dataset.page); return; }
  const docButton = event.target.closest('[data-open-doc]');
  if (docButton) { showDocument(docButton.dataset.openDoc); return; }
  const history = event.target.closest('[data-show-history]');
  if (history) {
    el('documentDialog').close();
    page('versions');
    renderVersionPicker(history.dataset.showHistory);
    await loadHistory(history.dataset.showHistory);
    return;
  }
  const requestDoc = event.target.closest('[data-request-doc]');
  if (requestDoc) {
    el('documentDialog').close();
    page('requests');
    el('requestDocumentId').value = requestDoc.dataset.requestDoc;
    return;
  }
  const reviewButton = event.target.closest('[data-review-request]');
  if (reviewButton) {
    if (!currentUser || currentUser.role !== 'HIGHER_OFFICIAL') return;
    const decision = reviewButton.dataset.decision;
    const remarks = prompt(`Enter remarks for ${decision.toLowerCase()} request #${reviewButton.dataset.reviewRequest}:`);
    if (remarks === null) return;
    if (!remarks.trim()) { toast('Remarks are required.', true); return; }
    try {
      await api('/api/review-request', {requestId:reviewButton.dataset.reviewRequest, decision, remarks:remarks.trim()});
      await loadRequests();
      toast('Request ' + decision.toLowerCase() + '. Document not changed.');
    } catch (error) { toast(error.message, true); }
    return;
  }
  const suggestion = event.target.closest('[data-suggestion]');
  if (suggestion) {
    const words = el('searchQuery').value.split(/[ ,]+/);
    words.pop(); words.push(suggestion.dataset.suggestion);
    el('searchQuery').value = words.join(' ');
    el('suggestions').innerHTML = '';
    runSearch();
    return;
  }
  const change = event.target.closest('[data-change-version]');
  if (change) {
    if (!currentUser || currentUser.role !== 'HIGHER_OFFICIAL') return;
    if (!confirm(`Make version ${change.dataset.changeVersion} current for ${change.dataset.id}?`)) return;
    try {
      const result = await api('/api/change-version', {id:change.dataset.id,version:change.dataset.changeVersion});
      await refresh();
      renderVersionPicker(result.id);
      await loadHistory(result.id);
      toast(`Current version is now V${result.currentVersion}. Saved in MySQL.`);
    } catch (error) { toast(error.message, true); }
  }
});

el('mobileMenu').addEventListener('click', () => el('sidebar').classList.toggle('open'));
el('dialogClose').addEventListener('click', () => el('documentDialog').close());
el('documentDialog').addEventListener('click', (e) => { if (e.target === el('documentDialog')) el('documentDialog').close(); });
['documentFilter','departmentFilter','categoryFilter','dateFilter'].forEach(id => {
  const target = el(id);
  if (target) target.addEventListener(id === 'documentFilter' ? 'input' : 'change', renderDocuments);
});
el('keywordFilter').addEventListener('input', renderIndex);
document.querySelectorAll('.search-tab').forEach((b) => b.addEventListener('click', () => setSearchType(b.dataset.type)));
el('searchQuery').addEventListener('input', suggest);
el('searchForm').addEventListener('submit', runSearch);
el('versionDocSelect').addEventListener('change', (e) => loadHistory(e.target.value));

el('addDocumentForm').addEventListener('submit', async (event) => {
  event.preventDefault();
  if (!currentUser || currentUser.role !== 'HIGHER_OFFICIAL') return;
  const form = event.target;
  const formData = new FormData(form);
  try {
    const doc = await api('/api/add', formData);
    await refresh();
    form.reset();
    page('documents');
    toast(`Document ${doc.id} added successfully.` + (doc.hasPdf ? ' PDF uploaded and stored.' : ''));
  } catch (error) { toast(error.message, true); }
});

// Correction request is available only for Lower Official.
el('correctionForm').addEventListener('submit', async (event) => {
  event.preventDefault();
  if (!currentUser || currentUser.role !== 'LOWER_OFFICIAL') return;
  const form = event.target;
  try {
    const result = await api('/api/correction-request', Object.fromEntries(new FormData(form)));
    form.reset();
    await loadRequests();
    toast(`Request #${result.id} submitted for ${result.documentId}.`);
  } catch (error) { toast(error.message, true); }
});

async function loadRequests() {
  try {
    const requests = await api('/api/correction-requests');
    el('requestListTitle').textContent = currentUser.role === 'HIGHER_OFFICIAL'
      ? 'Requests from Lower Officials' : 'My Requests';
    el('correctionList').innerHTML = requests.length ?
      `<table class="data-table"><thead><tr><th>Request</th><th>Document</th><th>Raised By</th><th>Reason / Suggestion</th><th>Status</th><th>Review</th></tr></thead><tbody>` +
      requests.slice().reverse().map(r => `<tr><td>#${r.id}<br><small>${safe(r.createdAt)}</small></td><td>${safe(r.documentId)}</td><td>${safe(r.requestedBy)}</td><td><strong>${safe(r.reason)}</strong><p>${safe(r.suggestion)}</p></td><td><span class="tag">${safe(r.status)}</span><br><small>${safe(r.remarks)}</small></td><td>` +
      (currentUser.role === 'HIGHER_OFFICIAL' && r.status === 'PENDING'
        ? `<button class="button button-primary button-small" data-review-request="${r.id}" data-decision="APPROVED">Approve</button> <button class="button button-secondary button-small" data-review-request="${r.id}" data-decision="REJECTED">Reject</button>`
        : safe(r.reviewedBy || '—')) + `</td></tr>`).join('') + '</tbody></table>'
      : empty('No correction requests','Requests will appear here after a Lower Official submits one.');
  } catch (error) { toast(error.message, true); }
}

async function loadActivity() {
  if (!currentUser || currentUser.role !== 'HIGHER_OFFICIAL') return;
  try {
    const entries = await api('/api/activity-log');
    el('activityList').innerHTML = entries.length ?
      `<table class="data-table"><thead><tr><th>Date & Time</th><th>Official</th><th>Action</th><th>Document</th><th>Details</th></tr></thead><tbody>` +
      entries.slice().reverse().map(e => `<tr><td>${safe(e.dateTime)}</td><td>${safe(e.username)}</td><td>${safe(e.action)}</td><td>${safe(e.reference)}</td><td>${safe(e.details)}</td></tr>`).join('') + '</tbody></table>'
      : empty('No activities yet','New actions will be recorded here.');
  } catch (error) { toast(error.message, true); }
}

function renderProfile() {
  if (!currentUser) return;
  const name = currentUser.fullName || currentUser.username;
  const isHigher = currentUser.role === 'HIGHER_OFFICIAL';
  const roleTitle = isHigher ? 'Higher Official' : 'Viewer';
  const deptTitle = isHigher ? (currentUser.department || 'Unassigned') : 'General / All Departments';
  const scope = isHigher
    ? `Departmental Isolation (${currentUser.department || 'Assigned Department'})`
    : 'Authorized Document Catalogue across all departments (Read/Search/Download)';
  const perms = isHigher
    ? `Manage ${currentUser.department || 'assigned'} department documents, upload PDFs, create versions, and review departmental correction requests.`
    : 'Search, view, and download authorized PDF documents across all departments; submit correction requests (read-only).';

  el('profileAvatar').textContent = name.slice(0, 1).toUpperCase();
  el('profileFullName').textContent = name;
  el('profileRoleBadge').textContent = roleTitle;
  el('profileDeptBadge').textContent = currentUser.department || (isHigher ? 'Unassigned' : 'All Departments');

  el('profileFieldFullName').textContent = name;
  el('profileFieldUsername').textContent = currentUser.username;
  el('profileFieldRole').textContent = roleTitle;
  el('profileFieldDept').textContent = deptTitle;
  if (el('profileFieldScope')) {
    el('profileFieldScope').textContent = scope;
  }
  el('profileFieldPerms').textContent = perms;
  if (el('profileFieldStatus')) {
    el('profileFieldStatus').textContent = '● Active (Authenticated)';
  }
}

// Login form: only the server decides the user role.
function showWorkspace(user) {
  currentUser = user;
  csrfToken = user.csrf;
  const displayName = user.fullName || user.username;
  const roleText = user.role === 'HIGHER_OFFICIAL' ? 'Higher Official' : 'Viewer';
  const deptText = user.department ? ' · ' + user.department : '';
  el('loggedUser').textContent = displayName + ' (' + roleText + deptText + ')';
  el('workspace').classList.toggle('viewer', user.role === 'LOWER_OFFICIAL');
  el('workspace').classList.toggle('higher', user.role === 'HIGHER_OFFICIAL');
  el('workspace').hidden = false;
  el('loginScreen').hidden = true;
}

function showLogin() {
  currentUser = null;
  csrfToken = '';
  el('workspace').hidden = true;
  el('loginScreen').hidden = false;
  switchAuthTab('login');
}

function switchAuthTab(tab) {
  const isLogin = tab === 'login';
  const tabLog = el('tabLogin');
  const tabReg = el('tabRegister');
  if (tabLog && tabReg) {
    tabLog.classList.toggle('active', isLogin);
    tabReg.classList.toggle('active', !isLogin);
    tabLog.setAttribute('aria-selected', isLogin);
    tabReg.setAttribute('aria-selected', !isLogin);
  }
  const logView = el('loginView');
  const regView = el('registerView');
  if (logView) logView.hidden = !isLogin;
  if (regView) regView.hidden = isLogin;
  if (el('loginMessage')) el('loginMessage').textContent = '';
  if (el('registerMessage')) {
    el('registerMessage').textContent = '';
    el('registerMessage').className = 'login-error';
  }
}

const tabLogBtn = el('tabLogin');
if (tabLogBtn) tabLogBtn.addEventListener('click', () => switchAuthTab('login'));
const tabRegBtn = el('tabRegister');
if (tabRegBtn) tabRegBtn.addEventListener('click', () => switchAuthTab('register'));

el('loginForm').addEventListener('submit', async (event) => {
  event.preventDefault();
  el('loginMessage').className = 'login-error';
  el('loginMessage').textContent = '';
  const form = event.target;
  try {
    const user = await api('/api/login', Object.fromEntries(new FormData(form)));
    showWorkspace(user);
    form.reset();
    await refresh();
    page('dashboard');
  } catch (error) {
    el('loginMessage').textContent = error.message;
  }
});

const regForm = el('registerForm');
if (regForm) {
  regForm.addEventListener('submit', async (event) => {
    event.preventDefault();
    const regMsg = el('registerMessage');
    regMsg.textContent = '';
    regMsg.className = 'login-error';
    const form = event.target;
    const data = Object.fromEntries(new FormData(form));

    if (!data.password || data.password.length < 12) {
      regMsg.textContent = 'Password must be at least 12 characters long.';
      return;
    }

    try {
      const res = await fetch('/api/register', {
        method: 'POST',
        headers: {'Content-Type': 'application/x-www-form-urlencoded'},
        body: new URLSearchParams(data).toString()
      });
      const body = await res.json();
      if (!res.ok || !body.success) {
        throw new Error(body.message || 'Registration failed.');
      }
      toast('Registration successful! Please sign in.');
      form.reset();
      switchAuthTab('login');
      const loginUser = el('loginForm').elements['username'];
      if (loginUser) loginUser.value = data.username || '';
      el('loginMessage').className = 'login-success';
      el('loginMessage').textContent = 'Account created successfully! Please enter your password to sign in.';
    } catch (error) {
      regMsg.className = 'login-error';
      regMsg.textContent = error.message;
    }
  });
}

el('logoutButton').addEventListener('click', async () => {
  try { await api('/api/logout', {}); }
  catch (error) { toast(error.message, true); }
  showLogin();
});

async function startWebsite() {
  try {
    const user = await api('/api/me');
    showWorkspace(user);
    await refresh();
  } catch (error) { showLogin(); }
}

startWebsite();

