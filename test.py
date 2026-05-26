from bs4 import BeautifulSoup
soup = BeautifulSoup(open('home.html', 'r', encoding='utf-8', errors='ignore'), 'html.parser')
items = soup.select('.ml-item')
for item in items[:2]:
    print(item.prettify())
