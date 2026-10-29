# WebP validation fixtures

`lossless.webp`와 `lossy.webp`는 Pillow 12.2.0으로 생성한 16×16 단색 RGB 이미지다.
외부 이미지나 개인정보를 포함하지 않는다. JPEG/PNG는 `TestClothingImages`에서 Java ImageIO로 생성한다.

재생성:

```python
from pathlib import Path
from PIL import Image

target = Path("src/test/resources/clothing-images")
target.mkdir(parents=True, exist_ok=True)
image = Image.new("RGB", (16, 16), (24, 80, 140))
image.save(target / "lossless.webp", lossless=True)
image.save(target / "lossy.webp", quality=80)
```
