import React, {useState} from 'react';
import {ArrowLeft, Check, Copy} from 'lucide-react';
import {previousMonth, importDue, wasImported} from './monthly-import.js';

const currency = n => Number(n).toLocaleString('pt-BR', {style:'currency', currency:'BRL'});
const monthName = month => new Date(`${month}-15T12:00:00`).toLocaleDateString('pt-BR', {month:'long',year:'numeric'});
export default function ImportPrevious({bills, initialMonth, busy, error, onImport, onBack}) {
  const [target, setTarget] = useState(initialMonth);
  const [selected, setSelected] = useState([]);
  const valid = /^[0-9]{4}-(0[1-9]|1[0-2])$/.test(target) && target >= '1900-01' && target <= '9999-12';
  const candidates = valid ? bills.filter(b => b.due.startsWith(previousMonth(target))).sort((a,b) => a.due.localeCompare(b.due)) : [];
  const available = candidates.filter(b => !wasImported(bills, b.id, target));
  const chosen = available.filter(b => selected.includes(b.id));
  const allSelected = available.length > 0 && chosen.length === available.length;
  return <>
    <button type="button" className="link" onClick={onBack} disabled={busy}><ArrowLeft size={15}/> Voltar ao cadastro</button>
    <p className="eyebrow import-eyebrow">MENOS TRABALHO A CADA MÊS</p>
    <h2>Importar do mês anterior</h2>
    <p>Escolha quais contas deseja repetir. O histórico original será preservado.</p>
    <label>Mês de destino<input type="month" min="1900-01" max="9999-12" value={target} disabled={busy} onChange={e => {setTarget(e.target.value); setSelected([]);}} required/></label>
    {valid && <p className="import-period">De <strong>{monthName(previousMonth(target))}</strong> para <strong>{monthName(target)}</strong></p>}
    {!!available.length && <label className="import-select-all"><input type="checkbox" checked={allSelected} disabled={busy} onChange={() => setSelected(allSelected ? [] : available.map(b => b.id))}/> Selecionar todas as disponíveis ({available.length})</label>}
    <div className="import-list">{candidates.map(b => {
      const imported = wasImported(bills, b.id, target);
      return <label key={b.id} className={'import-item' + (imported ? ' imported' : '')}>
        <input type="checkbox" checked={!imported && selected.includes(b.id)} disabled={imported || busy} onChange={e => setSelected(ids => e.target.checked ? [...ids, b.id] : ids.filter(id => id !== b.id))}/>
        <span><strong>{b.title}</strong><small>{b.source || b.category}</small><small>{imported ? 'Já importada para este mês' : `Novo vencimento: ${importDue(b.due, target).split('-').reverse().join('/')}`}</small></span>
        <b>{currency(b.amount)}</b>
      </label>;
    })}</div>
    {valid && !candidates.length && <div className="empty"><Copy size={25}/><h3>Nenhuma conta no mês anterior</h3><p>Cadastre uma conta ou escolha outro mês de destino.</p></div>}
    {!valid && <p className="error" role="alert">Selecione um mês entre janeiro de 1900 e dezembro de 9999.</p>}
    <p className="notice">Os valores anteriores serão copiados: confira e ajuste após importar. As novas contas ficarão em aberto. Pix e códigos de barras antigos não serão copiados. Dias inexistentes no mês serão ajustados para o último dia.</p>
    {!!chosen.length && <div className="detail-line"><span>{chosen.length} conta(s) selecionada(s)</span><strong>{currency(chosen.reduce((sum,b) => sum + Number(b.amount), 0))}</strong></div>}
    {error && <p className="error" role="alert">{error}</p>}
    <button type="button" className="primary full" disabled={busy || !valid || !chosen.length} onClick={() => onImport(chosen.map(b => b.id), target)}><Check size={17}/>{busy ? 'Importando…' : `Importar ${chosen.length} conta(s)`}</button>
  </>;
}
