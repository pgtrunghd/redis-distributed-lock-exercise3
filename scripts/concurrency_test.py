"""Real HTTP concurrency across two JVMs, shared Redis and PostgreSQL.
Run docker compose up -d --wait and ./gradlew test bootJar first.
"""
import concurrent.futures, json, os, pathlib, subprocess, tempfile, threading, time, urllib.request, urllib.error

ROOT = pathlib.Path(__file__).resolve().parents[1]
PORTS = (18085, 18086)
processes = []


def request(port, path, body=None):
    req = urllib.request.Request(f'http://localhost:{port}{path}',
        data=None if body is None else json.dumps(body).encode(),
        headers={'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(req, timeout=50) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.load(error)


def wait_ready(port):
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        try:
            if request(port, '/api/products/0')[0] == 404:
                return
        except (urllib.error.URLError, TimeoutError):
            pass
        time.sleep(.25)
    raise AssertionError(f'Instance {port} failed to start')


def race(stock, quantity=1, buyers=100):
    status, product = request(PORTS[0], '/api/products', {'name': 'Concurrency test', 'stock': stock})
    assert status == 201, (status, product)
    product_id = product['id']
    barrier = threading.Barrier(buyers)
    def buy(i):
        barrier.wait(timeout=15)
        return request(PORTS[i % 2], f'/api/products/{product_id}/purchases', {'quantity': quantity})
    start = time.monotonic()
    with concurrent.futures.ThreadPoolExecutor(max_workers=buyers) as executor:
        results = list(executor.map(buy, range(buyers)))
    successful = [body for code, body in results if code == 201]
    rejected = [body for code, body in results if code == 409]
    assert len(successful) == stock // quantity, results
    assert len(rejected) == buyers - len(successful), results
    assert all(body['message'] == 'Sản phẩm đã hết hàng' for body in rejected)
    assert len({body['id'] for body in successful}) == len(successful)
    for port in PORTS:
        _, actual = request(port, f'/api/products/{product_id}')
        assert actual['stock'] == stock % quantity, actual
        assert actual['sold'] == len(successful) * quantity, actual
    exists = subprocess.check_output(['docker','compose','exec','-T','redis','redis-cli',
        'EXISTS',f'lock:product:{product_id}'], cwd=ROOT, text=True).strip()
    assert exists == '0', 'Lock leaked after requests completed'
    print(f'PASS: stock={stock}, quantity={quantity}, buyers={buyers}, '
          f'success={len(successful)}, sold-out={len(rejected)}, '
          f'remaining={actual["stock"]}, lock released, {time.monotonic()-start:.2f}s', flush=True)


with tempfile.TemporaryDirectory(prefix='flashsale-test-') as temp:
    try:
        java = str(pathlib.Path(os.environ['JAVA_HOME'])/'bin/java') if 'JAVA_HOME' in os.environ else 'java'
        for port in PORTS:
            with open(pathlib.Path(temp)/f'{port}.log', 'w') as log:
                processes.append(subprocess.Popen([java,'-jar',str(ROOT/'build/libs/redis-distributed-lock-exercise3-1.0.0.jar')],
                    cwd=ROOT, env=dict(os.environ, SERVER_PORT=str(port)), stdout=log, stderr=subprocess.STDOUT))
            wait_ready(port)
        race(1)
        race(10)
        race(3, quantity=2)
        status, product = request(PORTS[0], '/api/products', {'name': 'Validation', 'stock': 1})
        assert status == 201
        assert request(PORTS[0], f'/api/products/{product["id"]}/purchases', {'quantity': 0})[0] == 400
        assert request(PORTS[1], f'/api/products/{product["id"]}/purchases', {'quantity': 1})[0] == 201
        assert request(PORTS[0], '/api/products/0/purchases', {'quantity': 1})[0] == 404
        print('PASS: invalid quantity rejected, valid request still works, missing product returns 404', flush=True)
    except Exception:
        for file in pathlib.Path(temp).glob('*.log'):
            print(file.name, file.read_text()[-5000:])
        raise
    finally:
        for process in processes:
            process.terminate()
        for process in processes:
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
