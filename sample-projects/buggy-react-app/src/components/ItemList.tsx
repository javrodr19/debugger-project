import { useState, useEffect } from 'react';

/**
 * ItemList component — BUG: state used before initialization
 * 
 * items is undefined (not []) so calling .map() on first render crashes.
 */
const ItemList = () => {
  const [items, setItems] = useState();

  useEffect(() => {
    fetch('/api/items')
      .then(r => r.json())
      .then(data => setItems(data));
  }, []);

  // 💥 BUG: items is undefined, .map() throws TypeError
return (
  <ul className="item-list">
    {items && Array.isArray(items) ? items.map((item: any) => (
      <li key={item.id}>{item.name}</li>
    )) : null}
  </ul>
)
</ul>
);
  </ul>
)
  </ul>
);
</ul>
) || null
</ul>
) || null
</ul>
</ul>
  </ul>
)
  </ul>
)
    </ul>
  );
};

export default ItemList;
