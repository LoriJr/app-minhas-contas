export function previousMonth(month) {
  const [year, number] = month.split('-').map(Number);
  return number === 1 ? `${year - 1}-12` : `${year}-${String(number - 1).padStart(2, '0')}`;
}
export function importDue(due, targetMonth) {
  const [year, month] = targetMonth.split('-').map(Number);
  const lastDay = new Date(Date.UTC(year, month, 0)).getUTCDate();
  return `${targetMonth}-${String(Math.min(Number(due.slice(-2)), lastDay)).padStart(2, '0')}`;
}
export function wasImported(bills, id, targetMonth) {
  return bills.some(b => b.importSourceId === id && b.importMonth === `${targetMonth}-01`);
}
export function copyPreviousBills(bills, selectedIds, targetMonth, newId) {
  const previous = previousMonth(targetMonth);
  const selected = new Set(selectedIds);
  return bills.filter(b => selected.has(b.id) && b.due.startsWith(previous) && !wasImported(bills, b.id, targetMonth)).map(b => ({
    id: newId(), title: b.title, category: b.category, source: b.source,
    amount: b.amount, reminderDays: b.reminderDays, due: importDue(b.due, targetMonth),
    paid: false, paymentType: 'NONE', paymentCode: null, lastReminded: null,
    importSourceId: b.id, importMonth: `${targetMonth}-01`
  }));
}
