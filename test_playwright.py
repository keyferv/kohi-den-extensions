from playwright.sync_api import sync_playwright
import json

def run():
    with sync_playwright() as p:
        browser = p.chromium.launch()
        page = browser.new_page()
        
        # Listen for all network requests
        urls = []
        page.on("request", lambda request: urls.append(request.url) if "json" in request.url or "api" in request.url or "ajax" in request.url else None)
        
        page.goto("https://fanpelis.to/home", wait_until="networkidle")
        print("URLS INTERCEPTED:")
        for u in urls:
            print(u)
        
        # Also let's extract the HTML after React renders
        html = page.content()
        with open("rendered_home.html", "w", encoding="utf-8") as f:
            f.write(html)
            
        browser.close()

run()
