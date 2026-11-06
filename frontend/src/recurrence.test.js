import {test} from 'node:test';
import assert from 'node:assert/strict';
import {dueDate,projectDemo,editDemo,deleteDemo,migrateDemo} from './recurrence.js';
const series={id:'r',title:'Aluguel',category:'Moradia',source:'Contrato',amount:1800,dueDay:31,reminderDays:3,startMonth:'2026-01'};
test('vencimento mensal ajusta fevereiro sem perder o dia 31 nos meses seguintes',()=>{
  assert.equal(dueDate('2026-02',31),'2026-02-28');
  assert.equal(dueDate('2028-02',31),'2028-02-29');
  assert.equal(dueDate('2026-03',31),'2026-03-31');
});
test('novos meses aparecem sem importação e o pagamento pertence a um único mês',()=>{
  const data={series:[series],occurrences:[]};
  const january=projectDemo(data,'2026-01','2026-01')[0];
  data.occurrences=[{...january,paid:true}];
  const months=projectDemo(data,'2026-03','2026-02');
  assert.equal(months.length,3); assert.equal(months[0].paid,true);
  assert.equal(months[1].paid,false); assert.equal(months[2].paid,false);
  assert.equal(projectDemo(data,'2025-12','2026-01').filter(b=>b.billingMonth==='2025-12-01').length,0);
});
test('editar preserva histórico anterior e modifica próximos meses em aberto',()=>{
  const data={series:[series],occurrences:[]};
  const feb=projectDemo(data,'2026-02','2026-02')[1];
  const changed=editDemo(data,feb,{title:'Aluguel',amount:1900,dueDay:10},'2026-02');
  const months=projectDemo(changed,'2026-03','2026-02');
  assert.equal(months[0].amount,1800); assert.equal(months[1].amount,1900);
  assert.equal(months[2].amount,1900); assert.equal(months[2].due,'2026-03-10');
});
test('delete remove recorrência e histórico de todos os meses',()=>{
  const data={series:[series],occurrences:projectDemo({series:[series],occurrences:[]},'2026-02','2026-02')};
  const deleted=deleteDemo(data,'r');
  assert.deepEqual(projectDemo(deleted,'2027-01','2026-02'),[]);
});
test('migração une importações, conserva pagamento e descarta campos de pagamento',()=>{
  const original={id:'a',title:'Energia',category:'Energia',amount:100,due:'2026-01-31',paid:true,paymentCode:'antigo'};
  const copy={...original,id:'b',due:'2026-02-28',paid:false,importSourceId:'a'};
  const data=migrateDemo([original,copy]);
  assert.equal(data.series.length,1); assert.equal(data.series[0].dueDay,31);
  const months=projectDemo(data,'2026-03','2026-02');
  assert.equal(months.length,3); assert.equal(months[0].paid,true); assert.equal(months[1].paid,false);
  assert.equal(months[2].due,'2026-03-31'); assert.equal('paymentCode' in months[0],false);
});
test('edição em mês futuro preserva valores dos meses intermediários ainda não visitados',()=>{
  const data={series:[series],occurrences:[]};
  const may=projectDemo(data,'2026-05','2026-01').find(b=>b.billingMonth==='2026-05-01');
  const edited=editDemo(data,may,{amount:2000,dueDay:10},'2026-01');
  const april=projectDemo(edited,'2026-04','2026-01').find(b=>b.billingMonth==='2026-04-01');
  assert.equal(april.amount,1800); assert.equal(april.due,'2026-04-30');
  assert.equal(projectDemo(edited,'2026-06','2026-01').find(b=>b.billingMonth==='2026-06-01').amount,2000);
});
