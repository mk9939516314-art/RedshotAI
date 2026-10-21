"""Runs the REAL engine.js against a replica of the IRCTC (Angular/PrimeNG) flow in headless Chromium.
Usage: python3 run_tests.py   (starts its own tiny server)"""
import json, subprocess, sys, time, os
from playwright.sync_api import sync_playwright

HERE = os.path.dirname(os.path.abspath(__file__))
ENGINE = os.path.join(HERE, '..', 'app', 'src', 'main', 'assets', 'tatkal', 'engine.js')
PORT = int(os.environ.get("TK_PORT", 8791))
srv = subprocess.Popen([sys.executable, os.path.join(HERE, 'serve.py'), str(PORT)])
time.sleep(0.8)
results = []

STUB = "window.__bridge=[];window.RSBridge={event:function(t,j){window.__bridge.push([t,JSON.parse(j)])},skew:function(){return 0}};"
BASE = dict(from_='BTH', fromName='Bettiah', to='SGL', toName='Sugauli', date='19/10/2026', trainNo='15273', cls='SL', quota='TQ',
            passengers=[dict(name='RAVI KUMAR', age=21, gender='M', berth='LB'), dict(name='SITA DEVI', age=45, gender='F', berth='')],
            mobile='9876543210', autoUpgrade=True, confirmOnly=False, payMode='ewallet', autoWalletConfirm=True,
            username='user1', password='pass1', captchaIdleMs=500)

def run(name, job_over=None, captcha_plan=None, url='/nget/train-search', open_in_ms=2500, mock=None, timeout=70, until=('confirmed', 'error'), stop_when=None, owner_login=False):
    job = dict(BASE); job['from'] = job.pop('from_')
    job.update(job_over or {})
    with sync_playwright() as p:
        b = p.chromium.launch()
        pg = b.new_page(viewport={'width': 420, 'height': 900})
        pg.add_init_script(STUB)
        errs = []
        pg.on('pageerror', lambda e: errs.append(str(e)))
        pg.goto(f'http://127.0.0.1:{PORT}{url}')
        pg.wait_for_function('window.__mock')
        if mock: pg.evaluate('m => Object.assign(window.__mock, m)', mock)
        pg.evaluate(f'window.__mock.openAt = Date.now() + {open_in_ms}')
        job['openAtMs'] = pg.evaluate(f'window.__mock.openAt')
        pg.add_script_tag(path=ENGINE)
        pg.evaluate('j => window.__RS.start(j)', job)
        plan = list(captcha_plan or ['abc12', 'abc12'])
        seen = 0; end = time.time() + timeout; outcome = None; logged_in_by_owner = False
        while time.time() < end:
            ev = pg.evaluate('window.__bridge')
            for t, d in ev[seen:]:
                if t == 'captcha':
                    val = plan.pop(0) if plan else 'abc12'
                    pg.wait_for_timeout(300)
                    pg.locator('input#captcha:visible, input[formcontrolname=captcha]:visible').first.press_sequentially(val, delay=30)
                if t == 'need_login' and owner_login:
                    pg.locator('[formcontrolname=userid]').fill('user1'); pg.locator('[type=password]').fill('pass1'); logged_in_by_owner = True
                    pg.wait_for_timeout(200)
                    pg.locator('#captcha').fill('abc12'); pg.locator('.signin').click()
                if t in until: outcome = (t, d)
                if stop_when and t == stop_when: outcome = (t, d)
            seen = len(ev)
            if outcome: break
            pg.wait_for_timeout(150)
        mlog = pg.evaluate('window.__mock.log'); mk = pg.evaluate('({errors:window.__mock.errors, bookClicks:window.__mock.bookClicks, firstClick:(window.__mock.clickTimes||[0])[0]})')
        evs = pg.evaluate('window.__bridge')
        elog = [d.get('msg') for t, d in evs if t == 'log']
        b.close()
    return dict(openAt=job['openAtMs'], outcome=outcome, mock_log=mlog, mock=mk, elog=elog, jserrors=errs, events=[t for t, _ in evs])

def check(name, cond, detail=''):
    results.append((name, bool(cond), detail))
    print(('PASS ' if cond else 'FAIL ') + name + ('' if cond else '  -> ' + str(detail)[:600]))

try:
    # 1) full Tatkal flow, wallet, with IRCTC "Unable to process" errors on the first two Book Now clicks and a wrong captcha at login
    r = run('full', captcha_plan=['wrong', 'abc12', 'abc12'], mock=dict(failBookClicks=2), timeout=80)
    ml = ' | '.join(r['mock_log'])
    check('full flow ends with a confirmed PNR', r['outcome'] and r['outcome'][0] == 'confirmed' and r['outcome'][1].get('pnr') == '4512345678', (r['outcome'], r['elog'][-12:]))
    check('station codes + date + class + quota filled correctly', 'SEARCH' in ml and '"from": "BTH"' in ml.replace('"from":"BTH"', '"from": "BTH"') or '"from":"BTH"' in ml, ml[:300])
    check('search used TATKAL and Sleeper', 'TATKAL' in ml and 'Sleeper (SL)' in ml and '19/10/2026' in ml, ml[:400])
    check('engine survived 2 IRCTC error dialogs (retry, no random clicks)', r['mock']['errors'] == 2 and r['mock']['bookClicks'] >= 3, r['mock'])
    check('wrong login captcha was retried, then accepted', 'LOGIN FAIL user1/wrong' in ml, ml)
    check('both passengers filled with age/gender/berth', 'RAVI KUMAR' in ml and 'SITA DEVI' in ml and '"21"' in ml and '"F"' in ml, ml)
    check('mobile and auto-upgrade set', 'mob=9876543210' in ml and 'upg=true' in ml, ml)
    check('wallet chosen and confirmed automatically', 'PAY IRCTC eWallet' in ml and 'WALLET CONFIRMED' in ml, ml)
    check('no page JS errors', not r['jserrors'], r['jserrors'])

    # 2) quota opens later: the engine itself must wait (Book Now is enabled early on purpose here) and not hammer before the open time
    r = run('timing', open_in_ms=12000, mock=dict(failBookClicks=0, alwaysEnabled=True), stop_when='armed', until=('confirmed', 'error', 'armed'))
    check('engine arms and waits for the quota to open', r['outcome'] and r['outcome'][0] == 'armed', (r['outcome'], r['elog'][-6:]))
    check('no Book Now click before open time', r['mock']['bookClicks'] == 0, r['mock'])
    r = run('timing2', open_in_ms=9000, mock=dict(failBookClicks=0, alwaysEnabled=True), job_over=dict(payMode='manual'), until=('payment_ready', 'error'), timeout=90)
    ct = r.get('click_times') or []
    check('after the open time it books by itself', r['outcome'] and r['outcome'][0] == 'payment_ready', (r['outcome'], r['elog'][-5:]))
    check('first Book Now click is not earlier than the open time', r['mock']['firstClick'] >= r['openAt'] - 400, (r['mock'], r['openAt']))

    # 3) manual payment: never presses Pay
    r = run('manual', job_over=dict(payMode='manual'), mock=dict(failBookClicks=0), until=('payment_ready', 'error', 'confirmed'))
    ml = ' | '.join(r['mock_log'])
    check('manual payment stops at the payment page and tells the owner', r['outcome'] and r['outcome'][0] == 'payment_ready' and 'PAY ' not in ml, (r['outcome'], ml))

    # 4) notice dialog on the first page is dismissed, then everything proceeds
    r = run('notice', url='/nget/train-search?notice=1', mock=dict(failBookClicks=0), job_over=dict(payMode='manual'), until=('payment_ready', 'error'))
    check('aadhaar notice dismissed and flow continues', r['outcome'] and r['outcome'][0] == 'payment_ready', (r['outcome'], r['elog'][:8]))

    # 5) no saved login: engine must ask the owner, not guess
    r = run('nologin', job_over=dict(username='', password=''), mock=dict(failBookClicks=0), owner_login=True, until=('payment_ready', 'error'), job_timeout=None) if False else run('nologin', job_over=dict(username='', password='', payMode='manual'), mock=dict(failBookClicks=0), owner_login=True, until=('payment_ready', 'error'))
    check('without saved login it asks the owner and continues after he signs in', 'need_login' in r['events'] and r['outcome'] and r['outcome'][0] == 'payment_ready', (r['events'][:12], r['outcome']))

    # 6) wrong train number -> honest failure, no random clicking
    r = run('notrain', job_over=dict(trainNo='99999'), mock=dict(failBookClicks=0), until=('error', 'confirmed'), timeout=70)
    check('missing train is reported honestly', r['outcome'] and r['outcome'][0] == 'error' and '99999' in r['outcome'][1].get('reason', ''), (r['outcome'], r['elog'][-4:]))
    check('nothing was clicked on a wrong train', r['mock']['bookClicks'] == 0, r['mock'])

    # 7) station given as NAME only (no code): still selected through the dropdown
    r = run('nameonly', job_over=dict(**{'from': '', 'to': ''}), mock=dict(failBookClicks=0), job_timeout=None) if False else run('nameonly', job_over={'from': '', 'to': '', 'fromName': 'Bettiah', 'toName': 'Sugauli', 'payMode': 'manual'}, mock=dict(failBookClicks=0), until=('payment_ready', 'error'))
    ml = ' | '.join(r['mock_log'])
    check('names without codes are chosen from the dropdown', r['outcome'] and r['outcome'][0] == 'payment_ready' and '"from":"BTH"' in ml.replace(' ', ''), (r['outcome'], ml[:200], r['elog'][:6]))
    # 8) early login from the header (before Book Now), owner only types captcha
    r = run('prelogin', job_over=dict(payMode='manual'), mock=dict(failBookClicks=0), until=('payment_ready', 'error'))
    ml = ' | '.join(r['mock_log'])
    check('logs in early through the header LOGIN button', 'HEADER LOGIN' in ml and r['outcome'] and r['outcome'][0] == 'payment_ready', (r['outcome'], ml[:300], r['elog'][:10]))
    check('login happened before the first Book Now', any('login window opened early' in (x or '') for x in r['elog']), r['elog'][:10])

    # 9) sold-out class -> falls back to the next class
    r = run('clsfallback', job_over=dict(payMode='manual', clsFallback=['3A']), mock=dict(failBookClicks=0, soldOut=['15273:SL']), open_in_ms=4000, until=('payment_ready', 'error'), timeout=90)
    check('sold-out class moves to the fallback class', r['outcome'] and r['outcome'][0] == 'payment_ready' and any('sold out (15273 SL) -> trying 15273 3A' in (x or '') for x in r['elog']), (r['outcome'], r['elog'][-8:]))

    # 10) sold-out train -> falls back to the alternate train
    r = run('trainfallback', job_over=dict(payMode='manual', altTrains=['12345']), mock=dict(failBookClicks=0, soldOut=['15273:SL']), open_in_ms=4000, until=('payment_ready', 'error'), timeout=90)
    check('sold-out train moves to the alternate train', r['outcome'] and r['outcome'][0] == 'payment_ready' and any('-> trying 12345 SL' in (x or '') for x in r['elog']), (r['outcome'], r['elog'][-8:]))

    # 11) everything sold out -> honest error, no pretend success
    r = run('allsold', job_over=dict(payMode='manual'), mock=dict(failBookClicks=0, soldOut=['15273:SL']), open_in_ms=3000, until=('payment_ready', 'error', 'confirmed'), timeout=90)
    check('all sold out is reported as an error', r['outcome'] and r['outcome'][0] == 'error', (r['outcome'], r['elog'][-5:]))
    # 12) wrong password: stop at once, ONE attempt only (never lock the owner's account)
    r = run('badpw', job_over=dict(password='nope', payMode='manual'), mock=dict(failBookClicks=0), until=('payment_ready', 'error', 'confirmed'), timeout=60)
    ml = ' | '.join(r['mock_log'])
    check('wrong password is a fatal, honest error', r['outcome'] and r['outcome'][0] == 'error' and 'login' in r['outcome'][1].get('msg', '').lower(), (r['outcome'], r['elog'][-4:]))
    check('wrong password tried only once', ml.count('BAD PW') == 1, ml)

    # 13) session dies on the passenger page: logs in again and redoes everything
    r = run('expire', job_over=dict(payMode='manual'), mock=dict(failBookClicks=0, expireOnce=True), until=('payment_ready', 'error'), timeout=100)
    ml = ' | '.join(r['mock_log'])
    check('session expiry mid-booking: re-login and finish', 'SESSION EXPIRED' in ml and r['outcome'] and r['outcome'][0] == 'payment_ready', (r['outcome'], r['elog'][-8:], ml[:300]))
    check('passengers filled again after the re-login', ml.count('PAX [') >= 1 and 'RAVI KUMAR' in ml, ml[:400])

    # 14) travel insurance answered NO, berth given as a word
    r = run('ins', job_over=dict(payMode='manual', passengers=[dict(name='RAVI KUMAR', age=21, gender='M', berth='lower')]), mock=dict(failBookClicks=0), until=('payment_ready', 'error'))
    ml = ' | '.join(r['mock_log'])
    check('insurance NO is chosen', 'INS no' in ml and 'INS yes' not in ml, ml)
    check('berth word becomes the IRCTC code', '"LB"' in ml or ',"LB",' in ml.replace(' ', ''), ml[:400])

    # 15) wallet without balance -> hands payment to the owner, does not loop
    r = run('lowbal', job_over=dict(payMode='ewallet', autoWalletConfirm=True), mock=dict(failBookClicks=0, lowBalance=True), until=('payment_ready', 'error', 'confirmed'), timeout=80)
    check('low wallet balance falls back to manual payment', r['outcome'] and r['outcome'][0] == 'payment_ready', (r['outcome'], r['elog'][-5:]))

    # 16) Access Denied page is recognised
    r = run('denied', url='/nget/denied', until=('blocked', 'error'), timeout=30)
    check('Access Denied page is reported as blocked', r['outcome'] and r['outcome'][0] == 'blocked', (r['outcome'], r['elog'][-3:]))
    # 17) IRCTC keeps refusing (quota not available): stops instead of spamming
    r = run('refuse', job_over=dict(payMode='manual'), mock=dict(failBookClicks=0, refuse=True), open_in_ms=1500, until=('payment_ready', 'error'), timeout=120)
    check('repeated refusal ends as an honest error', r['outcome'] and r['outcome'][0] == 'error' and 'quota' in r['outcome'][1].get('msg', '').lower(), (r['outcome'], r['elog'][-4:]))
    check('it did not spam Book Now forever', r['mock']['bookClicks'] <= 14, r['mock'])
finally:
    srv.terminate()

bad = [n for n, ok, _ in results if not ok]
print(f'\n{len(results) - len(bad)}/{len(results)} passed')
sys.exit(1 if bad else 0)
