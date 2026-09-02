// 페이지에 주입되어 실행되는 추출기.
// chrome.scripting.executeScript 의 func 로 넘기므로 외부 스코프를 참조하지 않는 순수 함수여야 한다.

/**
 * 상품 상세 페이지에서 원본 데이터를 긁는다.
 * 서버의 SiteAdapter 와 짝이 맞는 형태로만 만들고, 해석/정규화는 서버에 맡긴다.
 */
export function extractProduct(siteCode) {
  const text = (selector) => document.querySelector(selector)?.textContent?.trim() || null;
  const attr = (selector, name) => document.querySelector(selector)?.getAttribute(name) || null;

  // 페이지가 심어두는 구조화 데이터가 있으면 그게 가장 정확하다.
  const readJsonLd = () => {
    for (const node of document.querySelectorAll('script[type="application/ld+json"]')) {
      try {
        const parsed = JSON.parse(node.textContent);
        const item = Array.isArray(parsed) ? parsed.find((p) => p['@type'] === 'Product') : parsed;
        if (item && item['@type'] === 'Product') return item;
      } catch {
        // 깨진 JSON-LD 는 무시하고 다음 후보로
      }
    }
    return null;
  };

  if (siteCode === 'taobao') {
    // 타오바오는 전역 변수에 상세 데이터를 실어 보낸다. DOM 파싱보다 안정적이다.
    const store = window.__INIT_DATA__ || window.g_config || null;
    const goods = store?.goods || {};
    const skus = Array.isArray(store?.skus) ? store.skus : [];
    return {
      goods: {
        itemId: goods.itemId || new URL(location.href).searchParams.get('id'),
        title: goods.title || text('h1') || document.title,
        price: goods.price || null,
        mainImage: goods.mainImage || attr('img[data-main]', 'src'),
      },
      skus,
    };
  }

  const ld = readJsonLd();
  const images = [...document.querySelectorAll('img[data-product-image]')].map((el) => el.src);

  return {
    siteCode,
    externalId:
      attr('[data-product-id]', 'data-product-id') ||
      ld?.sku ||
      new URL(location.href).searchParams.get('id'),
    title: ld?.name || text('h1') || document.title,
    price: ld?.offers?.price || text('[data-price]'),
    currency: ld?.offers?.priceCurrency || 'CNY',
    images: images.length ? images : ld?.image ? [].concat(ld.image) : [],
    options: [...document.querySelectorAll('[data-option]')].map((el) => ({
      name: el.dataset.optionName || '옵션',
      value: el.dataset.option,
      extraPrice: el.dataset.optionPrice || null,
      stock: el.dataset.optionStock ? Number(el.dataset.optionStock) : null,
      imageUrl: el.dataset.optionImage || null,
    })),
  };
}
