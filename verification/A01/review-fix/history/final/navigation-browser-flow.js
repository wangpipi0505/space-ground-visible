async (page) => {
  /** 独立生产dist导航5次：使用真实Chrome Performance API；LCP来自观察器，计时明确区分导航与接口/DOM交互。 */
  await page.addInitScript(()=>{window.__a01_lcp=[];new PerformanceObserver(list=>{for(const entry of list.getEntries())window.__a01_lcp.push({startTime:entry.startTime,size:entry.size})}).observe({type:'largest-contentful-paint',buffered:true})})
  const navigations=[],pages=[]
  for(let i=0;i<5;i++){
    await page.goto('http://127.0.0.1:15140',{waitUntil:'load'});await page.getByRole('button',{name:'筛选卫星',exact:true}).waitFor();await page.waitForTimeout(500)
    navigations.push(await page.evaluate(()=>{const n=performance.getEntriesByType('navigation')[0],paint=performance.getEntriesByType('paint'),r=performance.getEntriesByType('resource');return{ttfbMs:n.responseStart-n.requestStart,domContentLoadedMs:n.domContentLoadedEventEnd,loadMs:n.loadEventEnd,fcpMs:paint.find(x=>x.name==='first-contentful-paint')?.startTime??null,lcpMs:window.__a01_lcp.at(-1)?.startTime??null,jsHeap:performance.memory?{used:performance.memory.usedJSHeapSize,total:performance.memory.totalJSHeapSize,limit:performance.memory.jsHeapSizeLimit}:null,navigation:{transferSize:n.transferSize,encodedBodySize:n.encodedBodySize,decodedBodySize:n.decodedBodySize},resources:r.map(x=>({name:x.name,type:x.initiatorType,durationMs:x.duration,transferSize:x.transferSize,encodedBodySize:x.encodedBodySize,decodedBodySize:x.decodedBodySize}))}}))
  }
  for(const [name,selector] of [['弹道载荷配置','.ballistic-payload-view'],['102包轨迹拟合','.ballistic-fit-view'],['模拟弹道','.ballistic-simulation-view'],['弹道重定时','.ballistic-retime-view'],['任务结果记录','[data-test=task-result-records]'],['规划工作台','.planning-workbench']]){
    const start=Date.now();await page.getByRole('tab',{name,exact:true}).click();await page.locator(selector).waitFor({state:'visible'});await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));pages.push({name,tabClickToVisibleTwoFramesMs:Date.now()-start,domNodes:await page.locator(selector).locator('*').count()})
  }
  page.__a01_navigation={browser:await page.context().browser().version(),timezone:await page.evaluate(()=>Intl.DateTimeFormat().resolvedOptions().timeZone),navigations,pages,note:'导航5次均真实page.goto；首次冷访问与随后Chrome缓存导航分别保留。LCP是稳定500ms后的最后观测，不等于无限等待最终候选。JS heap为Chrome暴露的近似JS堆，不代表浏览器总内存。'}
  return page.__a01_navigation
}
