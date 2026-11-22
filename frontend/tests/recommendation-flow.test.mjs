import {test} from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";
import ts from "typescript";

function setup({match=null,pending=false,error=null}={}) {
    const calls=[];const exports={};
    const source=fs.readFileSync(new URL("../app/api/recommendation-flow.ts",import.meta.url),"utf8");
    const compiled=ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText;
    vm.runInNewContext(compiled,{exports,require(name){
        if(name==="./recommend")return{hasPendingRecommendation:async()=>{calls.push("pending");return pending;},
            recommendSake:async input=>{calls.push(["pay-sake",input]);return{food:input.food,recommendations:[]};},
            recommendFood:async input=>{calls.push(["pay-food",input]);return{sakeNo:input.sakeNo,recommends:[]};}};
        if(name==="./account")return{matchHistory:async input=>{calls.push(["match",input]);if(error)throw error;return match;}};
        throw new Error(name);
    }});return{...exports,calls};
}
test("matching personal result pauses before any paid request",async()=>{
    const h=setup({match:{id:"private-record"}});const decision=await h.prepareRecommendation({kind:"SAKE",food:"same"});
    assert.equal(decision.type,"duplicate");assert.equal(decision.previous.id,"private-record");assert.equal(h.calls.some(c=>Array.isArray(c)&&c[0].startsWith("pay")),false);
});
test("a new recommendation is charged only after explicit confirmation",async()=>{
    const h=setup({match:{id:"private-record"}});const input={kind:"FOOD",sakeNo:4};await h.prepareRecommendation(input);
    await h.requestNewRecommendation(input);assert.equal(h.calls.filter(c=>Array.isArray(c)&&c[0]==="pay-food").length,1);
});
test("no matching record proceeds to one paid request",async()=>{
    const h=setup();assert.equal((await h.prepareRecommendation({kind:"SAKE",food:"new"})).type,"completed");
    assert.equal(h.calls.filter(c=>Array.isArray(c)&&c[0]==="pay-sake").length,1);
});
test("an uncertain old request resumes its existing key without another duplicate prompt",async()=>{
    const h=setup({match:{id:"already-completed"},pending:true});assert.equal((await h.prepareRecommendation({kind:"SAKE",food:"same"})).type,"completed");
    assert.equal(h.calls.some(c=>Array.isArray(c)&&c[0]==="match"),false);
});
test("a duplicate-check failure never falls through to paid generation",async()=>{
    const h=setup({error:new Error("offline")});await assert.rejects(h.prepareRecommendation({kind:"SAKE",food:"same"}));
    assert.equal(h.calls.some(c=>Array.isArray(c)&&c[0].startsWith("pay")),false);
});
