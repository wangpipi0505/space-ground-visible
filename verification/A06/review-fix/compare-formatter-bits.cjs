/** 原Swing落表后重新parseDouble的位值，比较语义数值而非仅格式文本。 */
const fs = require('node:fs');
const rows = fs.readFileSync('G:/5-YG/space-ground-visible/verification/A06/review-fix/java-bits.stdout.txt','utf8').trim().split(/\r?\n/).map(JSON.parse);
const view = new DataView(new ArrayBuffer(8));
const comparisons = rows.map(row => {
  const formatted = new Intl.NumberFormat('en-US',{useGrouping:false,minimumFractionDigits:row.digits,maximumFractionDigits:row.digits}).format(Number(row.input));
  view.setFloat64(0,Number(formatted));
  const bits = view.getBigUint64(0).toString(16).padStart(16,'0');
  return {...row,jsFormatted:formatted,jsBits:bits,equal:row.bits===bits};
});
fs.writeFileSync('G:/5-YG/space-ground-visible/verification/A06/review-fix/formatter-bit-comparison.json',JSON.stringify(comparisons,null,2)+'\n');
const mismatches=comparisons.filter(row=>!row.equal);
console.log(JSON.stringify({cases:comparisons.length,stringDifferences:comparisons.filter(row=>row.formatted!==row.jsFormatted).length,mismatches},null,2));
if(mismatches.length) process.exitCode=1;
