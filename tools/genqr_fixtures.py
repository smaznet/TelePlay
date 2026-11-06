import qrcode, qrcode.util as u, qrcode.constants as c, sys
payloads = {
 "short": "HELLO",
 "url50": "http://192.168.100.200:45678/s/AbCdEfGhIjKlMnOpQrStUv",
 "v5": "http://192.168.100.200:45678/s/AbCdEfGhIjKlMnOpQrStUv?x=" + "y"*40,
 "v7": "u"*120,
 "v10": "z"*210,
}
for k,v in payloads.items():
    qr = qrcode.QRCode(error_correction=c.ERROR_CORRECT_M, border=0)
    qr.add_data(u.QRData(v, mode=u.MODE_8BIT_BYTE))
    qr.make(fit=True)
    qr.mask_pattern = qr.best_mask_pattern()
    qr.makeImpl(False, qr.mask_pattern)
    rows = ["".join('1' if x else '0' for x in r) for r in qr.get_matrix()]
    print(k, qr.version, qr.mask_pattern, len(rows))
    open(f"qr_{k}.txt","w").write(f"{qr.mask_pattern}\n"+v+"\n"+"\n".join(rows)+"\n")
