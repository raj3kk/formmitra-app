(function(){
          var out=[];
          function rect(el){
            try{
              var r=el.getBoundingClientRect();
              return {x:Math.round(r.x),y:Math.round(r.y),w:Math.round(r.width),h:Math.round(r.height)};
            }catch(e){ return null; }
          }
          function cls(e){
            try{ var c=e.getAttribute('class')||''; return (typeof c==='string')?c:''; }catch(x){ return ''; }
          }
          // v44: SIRF visible, on-screen, non-zero-size widgets gino.
          // Hidden/stale/zero-size captcha markup (display:none wale divs,
          // invisible sitekey holders) FALSE POSITIVE tha — yehi "screen par
          // captcha nahi tha phir bhi 3 try" ka root cause tha.
          function visible(el){
            try{
              var r=el.getBoundingClientRect();
              if(!r||r.width<=0||r.height<=0) return false;
              var cs=getComputedStyle(el);
              if(cs.display==='none'||cs.visibility==='hidden'||cs.visibility==='collapse') return false;
              var op=parseFloat(cs.opacity||'1');
              if(!(op>0)) return false;
              if(el.getAttribute('aria-hidden')==='true') return false;
              // v45: viewport intersection — off-screen (left:-9999px jaise)
              // non-zero elements bhi FALSE POSITIVE the. Virtual test gate
              // me pakda gaya (scenario: off-screen).
              var vw=window.innerWidth||document.documentElement.clientWidth||0;
              var vh=window.innerHeight||document.documentElement.clientHeight||0;
              if(r.right<=0||r.bottom<=0||r.left>=vw||r.top>=vh) return false;
              var p=el.parentElement, d=0;
              while(p&&d<4){
                var pcs=getComputedStyle(p);
                if(pcs.display==='none'||pcs.visibility==='hidden'||pcs.visibility==='collapse') return false;
                p=p.parentElement; d++;
              }
              return true;
            }catch(e){ return false; }
          }
          var invisible_markers=0;
          function push(kind, el, snippet){
            if(out.length>=10) return;
            if(!visible(el)){ invisible_markers++; return; }
            out.push({kind:kind, rect:rect(el), html_snippet:(snippet||'').slice(0,300)});
          }
          var docs=[document];
          try{
            Array.from(document.querySelectorAll('iframe')).forEach(function(f){
              try{ if(f.contentDocument) docs.push(f.contentDocument); }catch(e){}
            });
          }catch(e){}
          docs.forEach(function(doc){
            var iframes;
            try{ iframes=doc.querySelectorAll('iframe'); }catch(e){ return; }
            Array.from(iframes).forEach(function(f){
              var s=(f.src||'').toLowerCase(), kind=null;
              if(s.indexOf('recaptcha')>=0) kind='recaptcha';
              else if(s.indexOf('hcaptcha')>=0) kind='hcaptcha';
              else if(s.indexOf('turnstile')>=0||s.indexOf('challenges.cloudflare')>=0) kind='turnstile';
              if(!kind) return;
              // v45: passive badge ke andar wala iframe (v3 / v2-invisible ka
              // bottom-right badge) koi solvable challenge nahi hai — skip.
              // Asli v2 checkbox iframe kabhi badge div ke andar nahi hota.
              try{
                var bp=f.parentElement, bd=0, inBadge=false;
                while(bp&&bd<5){
                  var bc='';
                  try{ bc=bp.getAttribute('class')||''; }catch(x){ bc=''; }
                  if(typeof bc!=='string') bc='';
                  if(bc.toLowerCase().indexOf('grecaptcha-badge')>=0){ inBadge=true; break; }
                  bp=bp.parentElement; bd++;
                }
                if(inBadge) return;
              }catch(x){}
              push(kind, f, f.outerHTML);
            });
            var all;
            try{ all=doc.querySelectorAll('*'); }catch(e){ return; }
            Array.from(all).forEach(function(e){
              var c=(cls(e)+' '+(e.id||'')).toLowerCase();
              if(c.indexOf('captcha')<0) return;
              if(e.tagName==='IFRAME') return;
              // v45: passive reCAPTCHA v3 badge (grecaptcha-badge) koi solvable
              // challenge nahi hai — isko gine to FALSE POSITIVE. Virtual test
              // gate me pakda gaya (scenario: v3-passive-badge).
              if(c.indexOf('grecaptcha-badge')>=0) return;
              var kind='captcha_element';
              if(c.indexOf('hcaptcha')>=0) kind='hcaptcha';
              else if(c.indexOf('g-recaptcha')>=0||c.indexOf('recaptcha')>=0) kind='recaptcha';
              else if(e.tagName==='IMG') kind='image_captcha';
              push(kind, e, e.outerHTML);
            });
            var imgs;
            try{ imgs=doc.querySelectorAll('img'); }catch(e){ return; }
            Array.from(imgs).forEach(function(im){
              if((im.src||'').toLowerCase().indexOf('captcha')>=0) push('image_captcha', im, im.outerHTML);
              else if((im.alt||'').toLowerCase().indexOf('captcha')>=0) push('image_captcha', im, im.outerHTML);
            });
            // v14: data-sitekey wale elements (invisible recaptcha/turnstile)
            var sk;
            try{ sk=doc.querySelectorAll('[data-sitekey]'); }catch(e){ return; }
            Array.from(sk).forEach(function(e){
              var c=(cls(e)+' '+(e.id||'')).toLowerCase();
              var kind='recaptcha';
              if(c.indexOf('hcaptcha')>=0) kind='hcaptcha';
              else if(c.indexOf('turnstile')>=0||c.indexOf('cloudflare')>=0) kind='turnstile';
              push(kind, e, e.outerHTML);
            });
            // v14: aria-label me captcha (accessible widgets)
            var al;
            try{ al=doc.querySelectorAll('[aria-label]'); }catch(e){ return; }
            Array.from(al).forEach(function(e){
              try{
                var a=(e.getAttribute('aria-label')||'').toLowerCase();
                if(a.indexOf('captcha')>=0) push('captcha_element', e, e.outerHTML);
              }catch(x){}
            });
          });
          return JSON.stringify({found: out.length>0, widgets: out, invisible_markers: invisible_markers});
        })()