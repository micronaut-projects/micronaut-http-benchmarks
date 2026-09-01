{ pkgs }:
pkgs.runCommand "benchmark-tls" {
  nativeBuildInputs = [ pkgs.jdk25_headless pkgs.openssl ];
} ''
  install -Dm644 ${./tls}/ca-key.pem "$TMPDIR/ca-key.pem"
  install -Dm644 ${./tls}/server-key.pem "$out/server-key.pem"
  install -Dm644 ${./tls}/client-key.pem "$out/client-key.pem"
  install -d "$TMPDIR/newcerts"
  touch "$TMPDIR/index.txt"
  printf '1000\n' > "$TMPDIR/serial"
  cat > "$TMPDIR/openssl.cnf" <<EOF
  [ ca ]
  default_ca = CA_default
  [ CA_default ]
  database = $TMPDIR/index.txt
  new_certs_dir = $TMPDIR/newcerts
  serial = $TMPDIR/serial
  private_key = $TMPDIR/ca-key.pem
  certificate = $out/ca.pem
  default_md = sha256
  policy = policy_any
  copy_extensions = copy
  [ policy_any ]
  commonName = supplied
  [ ca_ext ]
  basicConstraints = critical,CA:true
  keyUsage = critical,keyCertSign,cRLSign
  subjectKeyIdentifier = hash
  authorityKeyIdentifier = keyid:always,issuer
  [ server_ext ]
  basicConstraints = critical,CA:false
  keyUsage = critical,digitalSignature,keyEncipherment
  extendedKeyUsage = serverAuth
  subjectAltName = DNS:example.com,DNS:localhost,IP:10.0.0.2,IP:127.0.0.1
  subjectKeyIdentifier = hash
  authorityKeyIdentifier = keyid,issuer
  [ client_ext ]
  basicConstraints = critical,CA:false
  keyUsage = critical,digitalSignature,keyEncipherment
  extendedKeyUsage = clientAuth
  subjectKeyIdentifier = hash
  authorityKeyIdentifier = keyid,issuer
  EOF
  openssl req -new -key "$TMPDIR/ca-key.pem" -subj '/CN=Micronaut Benchmark CA' -out "$TMPDIR/ca.csr"
  openssl ca -batch -selfsign -config "$TMPDIR/openssl.cnf" -in "$TMPDIR/ca.csr" \
    -startdate 20240101000000Z -enddate 20510101000000Z -extensions ca_ext -out "$out/ca.pem"
  openssl req -new -key "$out/server-key.pem" -subj '/CN=example.com' -out "$TMPDIR/server.csr"
  openssl ca -batch -config "$TMPDIR/openssl.cnf" -in "$TMPDIR/server.csr" \
    -startdate 20240101000000Z -enddate 20510101000000Z -extensions server_ext -out "$out/server.pem"
  openssl req -new -key "$out/client-key.pem" -subj '/CN=Micronaut Benchmark Client' -out "$TMPDIR/client.csr"
  openssl ca -batch -config "$TMPDIR/openssl.cnf" -in "$TMPDIR/client.csr" \
    -startdate 20240101000000Z -enddate 20510101000000Z -extensions client_ext -out "$out/client.pem"

  openssl pkcs12 -export -name benchmark -in "$out/server.pem" -inkey "$out/server-key.pem" \
    -certfile "$out/ca.pem" -passout pass:password -out "$out/server.p12"
  openssl pkcs12 -export -name benchmark-client -in "$out/client.pem" -inkey "$out/client-key.pem" \
    -certfile "$out/ca.pem" -passout pass:password -out "$out/client.p12"

  openssl verify -CAfile "$out/ca.pem" "$out/server.pem" "$out/client.pem"
  openssl pkey -in "$out/server-key.pem" -noout
  openssl pkey -in "$out/client-key.pem" -noout
  test "$(openssl x509 -noout -modulus -in "$out/server.pem" | openssl sha256)" = \
    "$(openssl rsa -noout -modulus -in "$out/server-key.pem" | openssl sha256)"
  LC_ALL=C keytool -list -v -storetype PKCS12 -keystore "$out/server.p12" -storepass password -alias benchmark \
    | grep -Fx 'Entry type: PrivateKeyEntry'
  LC_ALL=C keytool -list -v -storetype PKCS12 -keystore "$out/client.p12" -storepass password -alias benchmark-client \
    | grep -Fx 'Entry type: PrivateKeyEntry'
''
