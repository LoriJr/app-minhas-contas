export function dueDate(month, day) {
  const [year, number] = month.split('-').map(Number);
  const last = new Date(Date.UTC(year, number, 0)).getUTCDate();
  return `${month}-${String(Math.min(day, last)).padStart(2, '0')}`;
}
export function nextMonth(month) {
  const [year, number] = month.split('-').map(Number);
  return number === 12 ? `${year + 1}-01` : `${year}-${String(number + 1).padStart(2, '0')}`;
}
export function occurrence(series, month) {
  return {id:`${series.id}:${month}`,recurringId:series.id,billingMonth:`${month}-01`,
    title:series.title,category:series.category,source:series.source,amount:series.amount,
    reminderDays:series.reminderDays,dueDay:series.dueDay,due:dueDate(month,series.dueDay),paid:false};
}
export function projectDemo(data, requested, current) {
  const known = new Map(data.occurrences.map(b=>[`${b.recurringId}:${b.billingMonth.slice(0,7)}`, b]));
  const valid = new Set(data.series.map(s=>s.id));
  for(const s of data.series) {
    for(let month=s.startMonth;month<=current;month=nextMonth(month)) {
      const key=`${s.id}:${month}`;
      if(!known.has(key)) known.set(key,occurrence(s,month));
    }
    const key=`${s.id}:${requested}`;
    if(requested>=s.startMonth&&!known.has(key)) known.set(key,occurrence(s,requested));
  }
  return [...known.values()].filter(b=>valid.has(b.recurringId)).sort((a,b)=>a.due.localeCompare(b.due));
}
export function editDemo(data, selected, input, current) {
  const snapshots=projectDemo(data,selected.billingMonth.slice(0,7),current);
  const original=data.series.find(s=>s.id===selected.recurringId);
  for(let month=current;month<selected.billingMonth.slice(0,7);month=nextMonth(month)) {
    if(month>=original.startMonth&&!snapshots.some(b=>b.recurringId===original.id&&b.billingMonth===`${month}-01`)) snapshots.push(occurrence(original,month));
  }
  const series=data.series.map(s=>s.id===selected.recurringId?{...s,...input}:s);
  const template=series.find(s=>s.id===selected.recurringId);
  const occurrences=snapshots.map(b=>b.recurringId===template.id&&(b.id===selected.id||(!b.paid&&b.billingMonth>selected.billingMonth))
    ?{...b,...input,due:dueDate(b.billingMonth.slice(0,7),input.dueDay)}:b);
  return {series,occurrences};
}
export function deleteDemo(data, recurringId) {
  return {series:data.series.filter(s=>s.id!==recurringId),occurrences:data.occurrences.filter(b=>b.recurringId!==recurringId)};
}
export function migrateDemo(legacy) {
  const seriesMap=new Map(), occurrences=[];
  const byId=new Map(legacy.map(b=>[b.id,b]));
  for(const b of legacy) {
    let root=b;const seen=new Set([b.id]);
    while(byId.has(root.importSourceId)&&!seen.has(root.importSourceId)) { root=byId.get(root.importSourceId);seen.add(root.id); }
    let id=root.id, month=b.due.slice(0,7);
    if(occurrences.some(o=>o.recurringId===id&&o.billingMonth===`${month}-01`)) id=b.id;
    const existing=seriesMap.get(id);
    if(!existing)seriesMap.set(id,{id,title:b.title,category:b.category,source:b.source,amount:b.amount,reminderDays:b.reminderDays,dueDay:Number(root.due.slice(-2)),startMonth:month,latestMonth:month});
    else {
      if(month<existing.startMonth)existing.startMonth=month;
      if(month>=existing.latestMonth)Object.assign(existing,{title:b.title,category:b.category,source:b.source,amount:b.amount,reminderDays:b.reminderDays,latestMonth:month});
    }
    occurrences.push({...occurrence(seriesMap.get(id),month),id:b.id,due:b.due,paid:!!b.paid,amount:b.amount,title:b.title,category:b.category,source:b.source,reminderDays:b.reminderDays});
  }
  return {series:[...seriesMap.values()],occurrences};
}
