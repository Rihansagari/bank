const $ = id => document.getElementById(id);
let accounts = [], transactions = [], toastTimer;
const rupees = n => '₹' + Number(n || 0).toLocaleString('en-IN',{minimumFractionDigits:2,maximumFractionDigits:2});
const safe = s => String(s ?? '').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
async function api(path, data) {
  const opts = data ? {method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams(data)} : {};
  const res = await fetch(path,opts);
  const result = await res.json();
  if (!res.ok) throw new Error(result.error || 'Something went wrong.');
  return result;
}
function toast(message,error=false) {
  const el=$('toast'); el.textContent=message; el.className='toast show'+(error?' error':'');
  clearTimeout(toastTimer); toastTimer=setTimeout(()=>el.className='toast',3200);
}
async function refresh() {
  try {
    [accounts,transactions] = await Promise.all([api('/api/accounts'),api('/api/transactions')]);
    const d = await api('/api/dashboard');
    $('accountCount').textContent=d.accountCount;
    $('totalBalance').textContent=rupees(d.totalBalance);
    renderAccounts(); renderTransactions(); fillSelects();
  } catch(e) { toast(e.message,true); }
}
function fillSelects() {
  const options=accounts.map(a=>`<option value="${a.id}">${safe(a.number)} - ${safe(a.name)} (${rupees(a.balance)})</option>`).join('');
  ['moneyAccount','fromAccount','toAccount'].forEach(id=>{
    const el=$(id),old=el.value; el.innerHTML=options;
    if(accounts.some(a=>String(a.id)===old)) el.value=old;
  });
}
function renderAccounts() {
  const q=$('search').value.toLowerCase();
  const list=accounts.filter(a=>(a.name+' '+a.number).toLowerCase().includes(q));
  $('accountsBody').innerHTML=list.map(a=>`<tr><td><strong>${safe(a.name)}</strong></td><td>${safe(a.number)}</td><td><span class="type">${safe(a.type)}</span></td><td class="balance">${rupees(a.balance)}</td></tr>`).join('');
  $('noAccounts').style.display=list.length?'none':'block';
}
function renderTransactions() {
  $('transactions').innerHTML=transactions.length?transactions.slice(0,12).map(t=>{
    const a=accounts.find(x=>x.id===t.accountId);
    const outgoing=['Withdrawal','Transfer Sent'].includes(t.type);
    const incoming=['Deposit','Transfer Received'].includes(t.type);
    const sign=outgoing?'−':incoming?'+':'';
    return `<div class="tx"><div class="tx-icon ${outgoing?'out':''}">${outgoing?'↗':incoming?'↙':'＋'}</div><div class="tx-info"><strong>${safe(t.type)}</strong><small>${safe(a?.name||'Account')} · ${safe(t.date)}</small></div><div class="tx-amount ${outgoing?'out':''}">${sign}${rupees(t.amount)}</div></div>`;
  }).join(''):'<p class="empty">No transactions yet.</p>';
}
$('search').addEventListener('input',renderAccounts);
$('createForm').addEventListener('submit',async e=>{
  e.preventDefault(); const f=new FormData(e.target);
  try {
    const r=await api('/api/create',{name:f.get('name'),type:f.get('type'),amount:f.get('amount')});
    e.target.reset(); await refresh(); toast(r.message+' · Account No: '+r.accountNumber);
  } catch(err){toast(err.message,true);}
});
$('moneyForm').addEventListener('submit',async e=>{
  e.preventDefault();
  const f=new FormData(e.target);
  const button=e.submitter;
  const operation=button?.value || (document.activeElement?.value) || 'deposit';
  try {
    const r=await api('/api/'+operation,{id:f.get('id'),amount:f.get('amount')});
    e.target.reset(); await refresh(); toast(r.message);
  } catch(err){toast(err.message,true);}
});
$('transferForm').addEventListener('submit',async e=>{
  e.preventDefault();const f=new FormData(e.target);
  if(f.get('from')===f.get('to'))return toast('Select two different accounts.',true);
  try {
    const r=await api('/api/transfer',{from:f.get('from'),to:f.get('to'),amount:f.get('amount')});
    e.target.reset();await refresh();toast(r.message);
  } catch(err){toast(err.message,true);}
});
refresh();
