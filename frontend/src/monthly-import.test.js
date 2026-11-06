import {test} from 'node:test';
import assert from 'node:assert/strict';
import {previousMonth, importDue, copyPreviousBills, wasImported} from './monthly-import.js';

test('vencimentos respeitam fevereiro, anos bissextos e virada de ano', () => {
  assert.equal(previousMonth('2027-01'), '2026-12');
  assert.equal(importDue('2026-01-31','2026-02'),'2026-02-28');
  assert.equal(importDue('2028-01-31','2028-02'),'2028-02-29');
  assert.equal(importDue('2026-12-10','2027-01'),'2027-01-10');
});
test('demonstração copia apenas a seleção, preserva histórico e descarta pagamento antigo', () => {
  const source = {id:'a',title:'Energia',source:'Enel',category:'Energia',amount:186.42,due:'2026-10-31',paid:true,paymentType:'PIX',paymentCode:'antigo',reminderDays:3};
  const other = {...source,id:'b'};
  const result = copyPreviousBills([source,other],['a'],'2026-11',()=>'new');
  assert.equal(result.length,1);
  assert.equal(result[0].id,'new');
  assert.equal(result[0].due,'2026-11-30');
  assert.equal(result[0].amount,186.42);
  assert.equal(result[0].paid,false);
  assert.equal(result[0].paymentType,'NONE');
  assert.equal(result[0].paymentCode,null);
  assert.equal(source.paymentCode,'antigo');
  assert.equal(source.paid,true);
});
test('reimportação não duplica e nunca copia outros meses', () => {
  const source = {id:'a',due:'2026-10-10'};
  const copy = {id:'b',due:'2026-12-10',importSourceId:'a',importMonth:'2026-11-01'};
  assert.equal(wasImported([copy],'a','2026-11'),true);
  assert.deepEqual(copyPreviousBills([source,copy],['a'],'2026-11',()=>''),[]);
  assert.deepEqual(copyPreviousBills([source],['a'],'2026-12',()=>''),[]);
});
