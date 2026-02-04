-- Pandoc Lua filter to resize images to fit within page width
-- This filter modifies Image elements to use max-width constraint

function Image(el)
  -- Set width to 100% of text width if not already constrained
  -- This ensures images don't exceed the page margins
  el.attributes["width"] = "100%"
  return el
end

