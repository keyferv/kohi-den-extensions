import requests

url = "https://pelisjuanita.com/series/ver-serie/33-dias"

headers = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    "Cookie": (
        "cf_clearance=jZ84IVTGSoWKl430SDqn1pq3q.kuKvoWRUBGJ1sOfgI-1781561913-1.2.1.1-pIePEDInLP.Covf5_gAEqGeZxGAQ5dLoXSgTT.YrhcMtmdKve.p6iWpfDXQGUwzAdqZD2liR.beLwnK9HnY18rTOFAgR.7pymS6lP.vOsNgXe9xuJrELsEsJCy3Xgv4lyznp3PjCyHERgfS8fuB6wjjyxeHKPuE1lICO0UQEqLPDR2WWJG3Gdo3VI3_Jws0kuEK0scjNf6Xx2G2XRHNl2wPNXNcyNfS9eZEAR7IFRI9c_Rs4cqSKDv1LWkoY1grHBm738i.ZrWH.PRqLTsEWYqlqzlF4SUVWyCy..syfZW9h8ezwfba9pnnWjxN5YLQvq1.0vyT38MaerniCNny02VAMmas9osjTDHPAo3QxI43pAI._6qaytLKMdc_9Y4M1W3shwvJ.55aGrLrDl0xsn1_xHgh_hvIDMy.S8JqbSOg; "
        "PHPSESSID=bk0b9j3m96ijp8onsl6emama0r; "
        "first_visit=1; "
        "SeriesVistas=%5B%2261889%22%2C%22242866%22%5D; "
        "user_identifier=0a5ca7757505f29e424f7e12"
    ),
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8",
    "Accept-Language": "es-ES,es;q=0.9"
}

try:
    response = requests.get(url, headers=headers)
    print(f"Status Code: {response.status_code}")
    print(f"Content Length: {len(response.text)}")
    with open("raw_33dias.html", "w", encoding="utf-8") as f:
        f.write(response.text)
    print("Saved response to raw_33dias.html")
except Exception as e:
    print(f"Error: {e}")
